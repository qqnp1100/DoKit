package example;
public class Subject {
    public static int branch(int value) { try { return Math.abs(value); } finally { Math.abs(7); } }
    public static int implicitException() { return Integer.parseInt("bad"); }
    public static void explicitException() { throw new IllegalStateException("fixture"); }
    public int instance(int value) { return Math.abs(value); }
    public static void entry() { A.work(); }
    public static class A { public static void work() { B.work(); } }
    public static class B { public static void work() { C.work(); } }
    public static class C { public static void work() { Math.abs(-1); A.work(); } }
}
