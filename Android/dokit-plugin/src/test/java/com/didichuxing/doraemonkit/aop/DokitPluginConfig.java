package com.didichuxing.doraemonkit.aop;
import java.util.Map;
public class DokitPluginConfig { public static Map<?,?> CONFIG; public static void inject(Map<?,?> config) { CONFIG=config; } }
