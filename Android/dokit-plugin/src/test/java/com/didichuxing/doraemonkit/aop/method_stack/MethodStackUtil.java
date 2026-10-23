package com.didichuxing.doraemonkit.aop.method_stack;
public class MethodStackUtil {
 public static final MethodStackUtil INSTANCE = new MethodStackUtil();
 public void recodeStaticMethodCostStart(int depth, int threshold, int level, String owner, String name, String desc) {}
 public void recodeStaticMethodCostEnd(int threshold, int level, String owner, String name, String desc) {}
 public void recodeObjectMethodCostStart(int depth, int threshold, int level, String owner, String name, String desc, Object self) {}
 public void recodeObjectMethodCostEnd(int threshold, int level, String owner, String name, String desc, Object self) {}
}
