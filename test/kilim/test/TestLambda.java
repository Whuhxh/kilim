package kilim.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import junit.framework.TestCase;
import kilim.ExitMsg;
import kilim.Mailbox;
import kilim.Pausable;
import kilim.Task;
import kilim.test.TestLambda.Demo.SerSpawn;
import static kilim.test.TestYield.runTask;
import static kilim.test.ex.ExCatch.restoreArgument;
import static kilim.test.ex.ExYieldBase.fd;
import static kilim.test.ex.ExYieldBase.verify;

public class TestLambda extends TestCase {
    public void testLambda() throws Exception {
        // loop to ensure that multiple invocations of the invokedynamic instruction work as expected
        for (int i = 1; i < 10; i++) {
            ExitMsg e = new LamTask("testLambda", i, i * 2).start().joinb();
            if (!(e.result.equals("testLambda" + ( i + i*2)))) {
                fail("Expected testLambda<a+b>");
            }
        }
    }
    
    
    public void testSpawn() throws Exception {
        Mailbox<Integer> mb = new Mailbox<Integer>();
        Task t1 = Task.fork(() -> {
            Task.sleep(100);
            Integer i = mb.get();
            Task.exit(i);
        });
        Task t2 = Task.fork(() -> {
            mb.put(100);
        });
        
        t2.joinb();
        ExitMsg m = t1.joinb();
        if ((Integer)m.result != 100) {
            fail("Expected 100");
        }
    }
    
    
    public void testManualSpawn() throws Exception {
        // give normal (non-lambda) instances of Spawnable to spawn.
        
        Mailbox<Integer> mb = new Mailbox<Integer>();
        Task t1 = Task.fork( new Pausable.Fork() {
            @Override
            public void execute() throws Pausable {
                Integer i = mb.get();
                Task.exit(i);
            }
        });
        
        Task t2 = Task.fork( new Pausable.Fork() {
            @Override
            public void execute() throws Pausable {
                Task.sleep(100);
                mb.put(100);
            }
        });

        t2.joinb();
        ExitMsg m = t1.joinb();
        if ((Integer)m.result != 100) {
            fail("Expected 100");
        }
    }


    
    /**
     * Task that calls a pausable lambda method. We test with repeated pauses, captured variables
     * and explicit arguments. 
     */
    static class LamTask extends kilim.Task {
        private String s;
        private int a;
        private int b;

        public LamTask(String s, int a, int b) {
            this.s = s;
            this.a = a;
            this.b = b;
        }

        @Override
        public void execute() throws Pausable {
            int captured1 = this.a;
            int captured2 = this.b;
            
            Lambda<String> lam = (String input) -> {
                int c = 0;
                Task.yield();
                c += captured1;
                Task.sleep(10);
                c += captured2;
                Task.yield();
                return input + c;
            };
            String output = lam.process(this.s); // should be testLambda<a+b>
            Task.exit(output);
        }
    }
    
    static interface Lambda<T> {
        T process(String input) throws Pausable;
    }
    interface Foo { void run() throws Pausable; }
    void foo(Foo foo) throws Pausable { foo.run(); }

    void doLambda() throws Pausable {
        doLambda(fd);
    }
    void doLambda(Double resp) throws Pausable {
        try {
            foo(() -> restoreArgument(resp));
        }
        catch (Exception ex) {}
        verify(resp);
    }
    private static void runLambda(Task subtask) throws Exception {
        Task task = new Task.Fork(() -> subtask.execute());
        runTask(task);
    }

    // these tests are lambda duplicates of TestYield and TestYieldExceptions
    // reproduced here to stress the lambda/SAM processing
    // not included in the original files to limit the java8 dependency to this file
    // see the exclude8 variable in build.xml 

    public void testYieldExceptions() throws Exception {
        for (int ii=0; ii < 8; ii++)
            runLambda(new kilim.test.ex.ExCatch(ii));
        runTask(new Task.Fork(() -> doLambda(fd)));
        runLambda(new Task.Fork(() -> doLambda(fd)));
        runLambda(new Task.Fork(this::doLambda));
    }

    public void testYield() throws Exception {
        runLambda(new kilim.test.ex.ExYieldStack(0));
        runLambda(new kilim.test.ex.ExYieldStack(1));
        runLambda(new kilim.test.ex.ExYieldStack(2));
        runLambda(new kilim.test.ex.ExYieldStack(3));
        runLambda(new kilim.test.ex.ExYieldStack(4));
        runLambda(new kilim.test.ex.ExYieldDups(0));
        runLambda(new kilim.test.ex.ExYieldDups(1));
        runLambda(new kilim.test.ex.ExYieldDups(2));
        runLambda(new kilim.test.ex.ExYieldConstants(0));

        kilim.test.ex.ExLoop ex = new kilim.test.ex.ExLoop();
        runLambda(ex);
        assertTrue(ex.verify());
    }

    
    public static void testSerialization() {
        Demo.deser("hello world");
    }
    public static class Demo implements Serializable, Supplier<String> {
        public interface SerSpawn extends Serializable, Pausable.Fork {}
        String val;
        SerSpawn body2 = () -> {
            Task.sleep(50);
            val = "fork has slept: " + this.getClass();
        };
        public String get() { Task.fork(body2).joinb(); return val; }
        public static void deser(String args) {
            Demo body = new Demo();
            String v1 = body.get();
            byte [] bytes = save(body);
            Supplier<String> next = (Supplier<String>) load(bytes);
            String v2 = next.get();
            if (!v1.equals(v2)) fail("deserialized responce mismatch");
        }
    }
    public static byte [] save(Object obj) {
        ByteArrayOutputStream fos = null;
        ObjectOutputStream out = null;
        try {
            fos = new ByteArrayOutputStream();
            out = new ObjectOutputStream(fos);
            out.writeObject(obj);
            return fos.toByteArray();
        }
        catch (Exception ex) { fail("failed to save object to byte array: " + ex.getMessage()); }
        finally {
            try { out.close(); }
            catch (Exception ex) { fail("failed to close output stream: " + ex.getMessage()); }
        }
        return null;
    }
    public static Object load(byte [] bytes) {
        Loader loader = new Loader();
        loader.load(Demo.class);
        loader.load(SerSpawn.class);
        ObjectInputStream in = null;
        try {
            ByteArrayInputStream fin = new ByteArrayInputStream(bytes);
            in = loader.new ObjectStream(fin);
            return in.readObject();
        }
        catch (Exception ex) { fail("failed to load object from byte array: " + ex.getMessage()); }
        finally {
            try { in.close(); }
            catch (Exception ex) { fail("failed to close input stream: " + ex.getMessage()); }
        }
        return null;
    } 
    
    public static class Loader extends ClassLoader {
        public Loader() { super(TestLambda.class.getClassLoader()); }
        void load(Class klass) {
            String name = klass.getName();
            String cname = cname(name);
            ClassLoader cl = TestLambda.class.getClassLoader();
            InputStream in = cl.getResourceAsStream(cname);
            byte [] bytes = new byte[1<<14];
            try {
                int num = in.read(bytes);
                Class<?> c = super.defineClass(name,bytes,0,num);
                super.resolveClass(c);
            }
            catch (Exception ex) {
                fail("failed to define class: " + name + ", " + ex.getMessage());
            }
        }
        public class ObjectStream extends ObjectInputStream {
            public ObjectStream(final InputStream inputStream) throws IOException {
                super(inputStream);
            }
            @Override
            protected Class<?> resolveClass(final ObjectStreamClass klass) throws IOException, ClassNotFoundException {
                try {
                    return Class.forName(klass.getName(),false,Loader.this);
                } catch (ClassNotFoundException ex) {
                    return super.resolveClass(klass);
                }
            }
        }
    }
    static public String cname(String name) { return name.replace( '.', '/' ) + ".class"; }
}

