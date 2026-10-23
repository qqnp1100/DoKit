package com.didichuxing.doraemonkit.aop;
import java.util.*;
public class MethodCostUtil {
 public static final MethodCostUtil INSTANCE = new MethodCostUtil();
 public static final List<String> EVENTS = new ArrayList<>();
 public void recodeStaticMethodCostStart(int threshold, String method) { EVENTS.add("start:"+threshold+":"+method); }
 public void recodeStaticMethodCostEnd(int threshold, String method) { EVENTS.add("end:"+threshold+":"+method); }
 public void recodeObjectMethodCostStart(int threshold, String method, Object self) { EVENTS.add("start:"+threshold+":"+method); }
 public void recodeObjectMethodCostEnd(int threshold, String method, Object self) { EVENTS.add("end:"+threshold+":"+method); }
}
