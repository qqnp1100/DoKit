package example;
public class DispatchSubject {
    public interface Work { void run(); }
    public static class Parent { public void inherited() { Math.abs(-1); } }
    public static class Child extends Parent implements Work { public void run() { inherited(); } }
    public static class Other extends Parent implements Work {
        public void run() { Math.abs(-2); }
        public void run(int overload) { Math.abs(overload); }
    }
    public static void entry(Work worker, Child child) { worker.run(); child.inherited(); }
    public interface DefaultParent { default void inheritedDefault() { Math.abs(-3); } }
    public interface DefaultChild extends DefaultParent {}
    public static class DefaultWorker implements DefaultChild {}
    public static void defaultEntry(DefaultChild worker, DefaultWorker concrete) {
        worker.inheritedDefault(); concrete.inheritedDefault();
    }
}