package com.didichuxing.doraemonkit.plugin

import com.didichuxing.doraemonkit.DoKitReal
import com.didichuxing.doraemonkit.aop.*
import com.didichuxing.doraemonkit.aop.method_stack.MethodStackUtil
import com.didichuxing.doraemonkit.kit.core.DoKitManager
import com.didichuxing.doraemonkit.plugin.thirdlib.ThirdLibInfo
import example.Subject
import example.ActivitySubject
import example.DispatchSubject
import com.didichuxing.doraemonkit.aop.mc.DoKitProxyActivity
import org.junit.Assert.*
import org.junit.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import org.objectweb.asm.util.CheckClassAdapter
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.BasicVerifier
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

class BytecodeMigrationTest {
    private fun bytes(type: Class<*>) = type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }
    private val runtime = listOf(DoKitReal::class.java, MethodCostUtil::class.java, MethodStackUtil::class.java,
        DokitPluginConfig::class.java, DokitThirdLibInfo::class.java, DoKitManager::class.java)
        .associate { it.name.replace('.', '/') to bytes(it) }
    private val subjects = listOf(Subject::class.java, Subject.A::class.java, Subject.B::class.java, Subject.C::class.java)
        .associate { it.name.replace('.', '/') to bytes(it) }
    private fun state(options: Map<String, String> = emptyMap(), available: Set<String> = runtime.keys + subjects.keys,
        dependencies: List<ThirdLibInfo> = emptyList()) = DoKitState(options, ManifestInfo("example", emptySet()), available, dependencies)
    private fun node(data: ByteArray) = ClassNode().also { ClassReader(data).accept(it, ClassReader.EXPAND_FRAMES) }
    private fun verify(data: ByteArray) {
        val out = StringWriter()
        CheckClassAdapter.verify(ClassReader(data), javaClass.classLoader, false, PrintWriter(out))
        assertEquals(out.toString(), "", out.toString())
    }
    private fun load(name: String, data: ByteArray): Class<*> = object : ClassLoader(javaClass.classLoader) {
        fun define() = defineClass(name, data, 0, data.size)
    }.define()
    @Test fun normalModeBalancesReturnsImplicitAndExplicitExceptions() {
        val options = mapOf("DOKIT_METHOD_SWITCH" to "true", "DOKIT_METHOD_STRATEGY" to "1", "normal.threshold" to "70000", "normal.packages" to "example")
        val pipeline = DoKitBytecodePipeline(state(options), runtime + subjects)
        val data = pipeline.transform(subjects).getValue("example/Subject")
        verify(data)
        val klass = load("example.Subject", data)
        MethodCostUtil.EVENTS.clear()
        assertEquals(3, klass.getMethod("branch", Int::class.javaPrimitiveType).invoke(null, -3))
        assertEquals(2, MethodCostUtil.EVENTS.size)
        assertTrue(MethodCostUtil.EVENTS[0].startsWith("start:70000:"))
        assertTrue("normal keys contain overload descriptors", MethodCostUtil.EVENTS[0].endsWith("&(I)I"))
        assertTrue(MethodCostUtil.EVENTS[1].startsWith("end:70000:"))
        for (name in listOf("implicitException", "explicitException")) {
            MethodCostUtil.EVENTS.clear()
            try { klass.getMethod(name).invoke(null); fail("expected fixture failure") } catch (_: InvocationTargetException) {}
            assertEquals("balanced hooks for $name", 2, MethodCostUtil.EVENTS.size)
        }
        MethodCostUtil.EVENTS.clear()
        assertEquals(9, klass.getMethod("instance", Int::class.javaPrimitiveType).invoke(klass.getConstructor().newInstance(), -9))
        assertEquals(2, MethodCostUtil.EVENTS.size)
    }
    @Test fun stackGraphIsOrderIndependentAndBoundedAcrossClassesAndRecursion() {
        val options = mapOf("DOKIT_METHOD_SWITCH" to "true", "DOKIT_METHOD_STRATEGY" to "0", "DOKIT_METHOD_STACK_LEVEL" to "4",
            "stack.entries" to "example.Subject.entry", "stack.threshold" to "70000")
        val a = DoKitBytecodePipeline(state(options), runtime + subjects)
        val b = DoKitBytecodePipeline(state(options), runtime + subjects)
        val forward = a.transform(subjects)
        val reverse = b.transform(subjects.entries.reversed().associate { it.toPair() })
        assertEquals(a.selectedMethods, b.selectedMethods)
        assertEquals(listOf(0, 1, 2, 3), a.selectedMethods.values.toList().sorted())
        assertTrue(a.selectedMethods.keys.any { it.owner == "example/Subject$" + "C" })
        forward.forEach { (name, data) -> assertArrayEquals(data, reverse.getValue(name)); verify(data) }
        val shallow = DoKitBytecodePipeline(state(options + ("DOKIT_METHOD_STACK_LEVEL" to "1")), runtime + subjects)
        shallow.transform(subjects)
        assertEquals(1, shallow.selectedMethods.size)
        val blocked = DoKitBytecodePipeline(state(options + ("stack.blacklist" to "Subject$" + "B")), runtime + subjects)
        blocked.transform(subjects)
        assertEquals(2, blocked.selectedMethods.size)
        // A later variant starts from an empty task-local graph.
        val noEntry = DoKitBytecodePipeline(state(options + ("stack.entries" to "absent.Entry.enter")), runtime + subjects)
        noEntry.transform(subjects)
        assertTrue(noEntry.selectedMethods.isEmpty())
    }
    @Test fun coreConfigThirdLibrariesAndIndependentWebFlagsReachRuntime() {
        val options = mapOf("DOKIT_METHOD_STRATEGY" to "1", "webView.network" to "false", "webView.dokitWeb" to "true", "webView.vConsole" to "false")
        val pipeline = DoKitBytecodePipeline(state(options, dependencies = listOf(ThirdLibInfo("sample:library:1", 321))), runtime)
        val data = pipeline.transform(mapOf("com/didichuxing/doraemonkit/DoKitReal" to bytes(DoKitReal::class.java))).values.single()
        verify(data)
        val klass = load(DoKitReal::class.java.name, data)
        val instance = klass.getConstructor().newInstance()
        klass.getMethod("pluginConfig").invoke(instance)
        klass.getMethod("initThirdLibraryInfo").invoke(instance)
        assertEquals(true, DokitPluginConfig.CONFIG["dokitPluginSwitch"])
        assertEquals(1, DokitPluginConfig.CONFIG["methodStrategy"])
        assertEquals("321", DokitThirdLibInfo.INFO["sample:library:1"])
        assertFalse(DoKitManager.H5_JS_INJECT)
        assertTrue(DoKitManager.H5_DOKIT_MC_INJECT)
        assertFalse(DoKitManager.H5_VCONSOLE_INJECT)
        val second = DoKitBytecodePipeline(state(options, dependencies = listOf(ThirdLibInfo("sample:library:1", 321))), runtime)
            .transform(mapOf("com/didichuxing/doraemonkit/DoKitReal" to data)).values.single()
        assertArrayEquals("core injections are idempotent", data, second)
    }
    @Test fun pluginOffAndMissingRuntimeKeepOriginalBytes() {
        val off = DoKitBytecodePipeline(state(mapOf("DOKIT_PLUGIN_SWITCH" to "false")), runtime + subjects).transform(subjects)
        subjects.forEach { (key, value) -> assertArrayEquals(value, off.getValue(key)) }
        val absent = DoKitBytecodePipeline(state(available = subjects.keys), subjects)
        val output = absent.transform(subjects)
        subjects.forEach { (key, value) -> assertArrayEquals(value, output.getValue(key)) }
        assertTrue(absent.notices.single().contains("runtime absent"))
    }
    private fun fixture(name: String, descriptor: String, body: MethodVisitor.() -> Unit): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "test", descriptor, null, null).apply {
            visitCode(); body(); visitMaxs(0, 0); visitEnd()
        }
        writer.visitEnd(); return writer.toByteArray()
    }
    @Test fun optionalGpsHelpersAreRequiredBeforeIntroducingReferences() {
        val bytes = fixture("example/Gps", "(Landroid/location/LocationManager;Landroid/location/GpsStatus;)Landroid/location/GpsStatus;") {
            visitVarInsn(Opcodes.ALOAD, 0); visitVarInsn(Opcodes.ALOAD, 1)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/location/LocationManager", "getGpsStatus", "(Landroid/location/GpsStatus;)Landroid/location/GpsStatus;", false)
            visitInsn(Opcodes.ARETURN)
        }
        val input = mapOf("example/Gps" to bytes)
        val marker = "com/didichuxing/doraemonkit/gps_mock/Unrelated"
        val missing = DoKitBytecodePipeline(state(available = runtime.keys + input.keys + marker), runtime + input)
        val output = missing.transform(input).values.single()
        assertTrue(missing.notices.any { it.contains("GpsStatusUtil") })
        assertFalse(node(output).methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.any { it.owner.contains("gps_mock") })
        val available = runtime.keys + input.keys + "com/didichuxing/doraemonkit/gps_mock/location/GpsStatusUtil"
        val enabled = DoKitBytecodePipeline(state(available = available), runtime + input).transform(input).values.single()
        assertEquals(1, node(enabled).methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.count { it.owner.endsWith("GpsStatusUtil") })
    }
    @Test fun webViewMapOverloadAllocatesLocalsWithoutDebugTables() {
        val bytes = fixture("example/Web", "(Landroid/webkit/WebView;Ljava/lang/String;Ljava/util/Map;J)V") {
            visitVarInsn(Opcodes.ALOAD, 0); visitVarInsn(Opcodes.ALOAD, 1); visitVarInsn(Opcodes.ALOAD, 2)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/webkit/WebView", "loadUrl", "(Ljava/lang/String;Ljava/util/Map;)V", false)
            visitInsn(Opcodes.RETURN)
        }
        val hook = "com/didichuxing/doraemonkit/aop/WebViewHook"
        val input = mapOf("example/Web" to bytes)
        val transformed = DoKitBytecodePipeline(state(available = runtime.keys + input.keys + hook), runtime + input).transform(input).values.single()
        val method = node(transformed).methods.single()
        assertEquals(7, method.maxLocals)
        Analyzer(BasicVerifier()).analyze("example/Web", method)
        assertEquals(1, method.instructions.filterIsInstance<MethodInsnNode>().count { it.owner == hook && it.name == "inject" })
        val repeated = DoKitBytecodePipeline(state(available = runtime.keys + input.keys + hook), runtime + input).transform(mapOf("example/Web" to transformed)).values.single()
        assertArrayEquals(transformed, repeated)
    }
    @Test fun manifestNamesUseNamespaceAndDoNotLeakBetweenReads() {
        val file = Files.createTempFile("dokit-manifest", ".xml").toFile()
        try {
            for ((name, expected) in listOf(".App" to "example.namespace.App", "App" to "example.namespace.App", "full.App" to "full.App")) {
                file.writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="example.id"><application android:name="$name"/></manifest>""")
                assertEquals(setOf(expected), ManifestInfo.read(file, "example.namespace").applications)
            }
        } finally { file.delete() }
    }
    @Test fun activitySuperclassRewritePreservesSeparateAllocationsAndDelegation() {
        val name = ActivitySubject::class.java.name.replace('.', '/')
        val inputs = mapOf(name to bytes(ActivitySubject::class.java))
        val available = runtime + mapOf("android/app/Activity" to bytes(android.app.Activity::class.java),
            "com/didichuxing/doraemonkit/aop/mc/DoKitProxyActivity" to bytes(DoKitProxyActivity::class.java)) + inputs
        val transformed = DoKitBytecodePipeline(state(available = available.keys), available).transform(inputs).getValue(name)
        verify(transformed)
        val calls = node(transformed).methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.filter { it.name == "<init>" }
        assertEquals(2, calls.count { it.owner == "com/didichuxing/doraemonkit/aop/mc/DoKitProxyActivity" })
        assertEquals(3, calls.count { it.owner == "android/app/Activity" })
        val klass = load(ActivitySubject::class.java.name, transformed)
        klass.getConstructor().newInstance()
        klass.getConstructor(String::class.java).newInstance("delegated")
        assertEquals(DoKitProxyActivity::class.java, klass.superclass)
    }
    @Test fun stackGraphFindsInterfaceImplementationsInheritedMethodsAndExactOverloads() {
        val types = listOf(DispatchSubject::class.java, DispatchSubject.Work::class.java, DispatchSubject.Parent::class.java,
            DispatchSubject.Child::class.java, DispatchSubject.Other::class.java)
        val inputs = types.associate { it.name.replace('.', '/') to bytes(it) }
        val options = mapOf("DOKIT_METHOD_SWITCH" to "true", "DOKIT_METHOD_STRATEGY" to "0", "DOKIT_METHOD_STACK_LEVEL" to "4",
            "stack.entries" to "example.DispatchSubject.entry")
        val pipeline = DoKitBytecodePipeline(state(options, runtime.keys + inputs.keys), runtime + inputs)
        pipeline.transform(inputs).values.forEach(::verify)
        val selected = pipeline.selectedMethods
        assertEquals(4, selected.size)
        for (type in listOf("Child", "Other")) assertTrue(selected.keys.any { it.owner == "example/DispatchSubject$" + type && it.name == "run" && it.descriptor == "()V" })
        assertTrue(selected.keys.any { it.owner == "example/DispatchSubject$" + "Parent" && it.name == "inherited" })
        assertFalse(selected.keys.any { it.descriptor == "(I)V" })
    }
    @Test fun okHttpAndUrlConnectionHooksAreIdempotentAndRespectTheirSwitches() {
        val owner = "okhttp3/OkHttpClient"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC, "networkInterceptors", "Ljava/util/List;", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/util/List;)V", null, null).apply {
            visitCode(); visitVarInsn(Opcodes.ALOAD, 0); visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(Opcodes.ALOAD, 0); visitVarInsn(Opcodes.ALOAD, 1)
            visitFieldInsn(Opcodes.PUTFIELD, owner, "networkInterceptors", "Ljava/util/List;")
            visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
        }; writer.visitEnd()
        val url = fixture("example/Url", "(Ljava/net/URL;)Ljava/net/URLConnection;") {
            visitVarInsn(Opcodes.ALOAD, 0); visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/net/URL", "openConnection", "()Ljava/net/URLConnection;", false); visitInsn(Opcodes.ARETURN)
        }
        val inputs = mapOf(owner to writer.toByteArray(), "example/Url" to url)
        val available = runtime.keys + inputs.keys + setOf("com/didichuxing/doraemonkit/aop/OkHttpHook", "com/didichuxing/doraemonkit/aop/urlconnection/HttpUrlConnectionProxyUtil")
        val transformed = DoKitBytecodePipeline(state(available = available), runtime + inputs).transform(inputs)
        transformed.values.forEach { data -> val klass = node(data); klass.methods.forEach { Analyzer(BasicVerifier()).analyze(klass.name, it) } }
        assertEquals(1, node(transformed.getValue(owner)).methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.count { it.name == "addDoKitIntercept" })
        assertEquals(1, node(transformed.getValue("example/Url")).methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.count { it.name == "proxy" })
        val repeated = DoKitBytecodePipeline(state(available = available), runtime + transformed).transform(transformed)
        transformed.forEach { (key, value) -> assertArrayEquals(value, repeated.getValue(key)) }
        val off = DoKitBytecodePipeline(state(mapOf("networkEnable" to "false"), available), runtime + inputs).transform(inputs)
        assertFalse(node(off.getValue(owner)).methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.any { it.name == "addDoKitIntercept" })
    }

    private fun instanceFixture(owner: String, methods: List<Pair<String, String>>): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        methods.forEach { (name, descriptor) -> writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null).apply {
            visitCode()
            if (name == "<init>") { visitVarInsn(Opcodes.ALOAD, 0); visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false) }
            when (Type.getReturnType(descriptor).sort) {
                Type.VOID -> visitInsn(Opcodes.RETURN)
                Type.INT -> { visitInsn(Opcodes.ICONST_0); visitInsn(Opcodes.IRETURN) }
                else -> { visitInsn(Opcodes.ACONST_NULL); visitInsn(Opcodes.ARETURN) }
            }
            visitMaxs(0, 0); visitEnd()
        } }
        writer.visitEnd(); return writer.toByteArray()
    }
    @Test fun mapSdkRulesKeepSupportedSignaturesAndDoNotWrapListenersTwice() {
        val inputs = mapOf(
            "com/amap/api/location/AMapLocationClient" to listOf(
                "setLocationListener" to "(Lcom/amap/api/location/AMapLocationListener;)V",
                "unRegisterLocationListener" to "(Lcom/amap/api/location/AMapLocationListener;)V",
                "getLastKnownLocation" to "()Lcom/amap/api/location/AMapLocation;"),
            "com/amap/api/navi/AMapNavi" to listOf(
                "addAMapNaviListener" to "(Lcom/amap/api/navi/AMapNaviListener;)V",
                "removeAMapNaviListener" to "(Lcom/amap/api/navi/AMapNaviListener;)V"),
            "com/baidu/location/LocationClient" to listOf(
                "registerLocationListener" to "(Lcom/baidu/location/BDLocationListener;)V",
                "registerLocationListener" to "(Lcom/baidu/location/BDAbstractLocationListener;)V",
                "unRegisterLocationListener" to "(Lcom/baidu/location/BDLocationListener;)V",
                "unRegisterLocationListener" to "(Lcom/baidu/location/BDAbstractLocationListener;)V"),
            "com/tencent/map/geolocation/TencentLocationManager" to listOf(
                "requestSingleFreshLocation" to "(Ljava/lang/Object;Lcom/tencent/map/geolocation/TencentLocationListener;Ljava/lang/Object;)I",
                "requestLocationUpdates" to "(Ljava/lang/Object;Lcom/tencent/map/geolocation/TencentLocationListener;Ljava/lang/Object;)I",
                "removeUpdates" to "(Lcom/tencent/map/geolocation/TencentLocationListener;)V")
        ).mapValues { (owner, methods) -> instanceFixture(owner, methods) }
        val helpers = listOf("AMapLocationListenerProxy", "AMapLocationClientProxy", "AMapNaviListenerProxy", "ThirdMapLocationListenerUtil",
            "BDLocationListenerProxy", "BDAbsLocationListenerProxy", "TencentLocationListenerProxy").map { "com/didichuxing/doraemonkit/gps_mock/map/$it" }
        val available = runtime.keys + inputs.keys + helpers
        val transformed = DoKitBytecodePipeline(state(available = available), runtime + inputs).transform(inputs)
        transformed.values.forEach { data ->
            val klass = node(data)
            klass.methods.forEach { method ->
                Analyzer(BasicVerifier()).analyze(klass.name, method)
                assertTrue("each supported SDK method keeps its proxy", method.instructions.filterIsInstance<MethodInsnNode>().any { it.owner.contains("gps_mock/map") })
            }
        }
        val repeated = DoKitBytecodePipeline(state(available = available), runtime + transformed).transform(transformed)
        transformed.forEach { (key, value) -> assertArrayEquals(value, repeated.getValue(key)) }
        val malformed = mapOf("com/amap/api/location/AMapLocationClient" to instanceFixture("com/amap/api/location/AMapLocationClient", listOf("setLocationListener" to "(Ljava/lang/String;)V")))
        val untouched = DoKitBytecodePipeline(state(available = available), runtime + malformed).transform(malformed).values.single()
        assertFalse(node(untouched).methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.any { it.owner.contains("gps_mock") })
    }
    @Test fun imageRulesKeepAllFiveLibrariesAndConstructorOperandStacksValid() {
        val inputs = mapOf(
            "com/bumptech/glide/request/SingleRequest" to "(Ljava/lang/Object;)V",
            "com/squareup/picasso/Request" to "(Ljava/lang/Object;)V",
            "com/facebook/imagepipeline/request/ImageRequest" to "(Lcom/facebook/imagepipeline/request/ImageRequestBuilder;)V",
            "com/nostra13/universalimageloader/core/ImageLoadingInfo" to "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Lcom/nostra13/universalimageloader/core/listener/ImageLoadingListener;)V",
            "coil/request/ImageRequest" to "(Ljava/lang/Object;)V"
        ).mapValues { (owner, descriptor) -> instanceFixture(owner, listOf("<init>" to descriptor)) }
        val helpers = listOf("glide/GlideHook", "picasso/PicassoHook", "fresco/FrescoHook", "imageloader/ImageLoaderHook", "coil/CoilHook")
            .map { "com/didichuxing/doraemonkit/aop/bigimg/$it" }
        val available = runtime.keys + inputs.keys + helpers
        val transformed = DoKitBytecodePipeline(state(available = available), runtime + inputs).transform(inputs)
        transformed.values.forEach { data ->
            val klass = node(data)
            klass.methods.forEach { Analyzer(BasicVerifier()).analyze(klass.name, it) }
            assertTrue(klass.methods.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.any { it.owner.contains("aop/bigimg") })
        }
        val repeated = DoKitBytecodePipeline(state(available = available), runtime + transformed).transform(transformed)
        transformed.forEach { (key, value) -> assertArrayEquals(value, repeated.getValue(key)) }
        val off = DoKitBytecodePipeline(state(mapOf("bigImageEnable" to "false"), available), runtime + inputs).transform(inputs)
        assertFalse(off.values.flatMap { node(it).methods }.flatMap { it.instructions.filterIsInstance<MethodInsnNode>() }.any { it.owner.contains("aop/bigimg") })
    }

    @Test fun defaultNormalPackagesUseCodeNamespaceWhenApplicationIdHasSuffix() {
        val file = Files.createTempFile("dokit-suffixed-manifest", ".xml").toFile()
        try {
            file.writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.install.debug"><application android:name=".App"/></manifest>""")
            val manifest = ManifestInfo.read(file, "com.example.code")
            assertEquals("com.example.install.debug", manifest.packageName)
            assertEquals("com.example.code", manifest.namespace)
            assertEquals(setOf("com.example.code.App"), manifest.applications)
            val state = DoKitState(emptyMap(), manifest, emptySet())
            assertEquals(setOf("com.example.code"), state.slowMethodExt.normalMethod.packageNames)
        } finally { file.delete() }
    }

    @Test fun legacyFinallySubroutinesAreInlinedBeforeFramesAndKeepExceptionSemantics() {
        val original = LegacyBytecodeFixtures.jsrFinally()
        assertTrue(node(original).methods.single().instructions.any { it.opcode == Opcodes.JSR })
        val originalClass = load("example.LegacyFinally", original)
        assertEquals(4, originalClass.getMethod("compute", String::class.java).invoke(null, "4"))
        val input = mapOf("example/LegacyFinally" to original)
        val options = mapOf("DOKIT_METHOD_SWITCH" to "true", "DOKIT_METHOD_STRATEGY" to "1", "normal.packages" to "example")
        val pipeline = DoKitBytecodePipeline(state(options, runtime.keys + input.keys), runtime + input)
        val transformed = pipeline.transform(input).values.single()
        verify(transformed)
        assertFalse(node(transformed).methods.single().instructions.any { it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET })
        assertTrue(pipeline.notices.any { it.contains("example/LegacyFinally.compute(Ljava/lang/String;)I") })
        val klass = load("example.LegacyFinally", transformed)
        MethodCostUtil.EVENTS.clear()
        assertEquals(7, klass.getMethod("compute", String::class.java).invoke(null, "7"))
        assertEquals(1, klass.getField("finallyCalls").get(null))
        assertEquals(2, MethodCostUtil.EVENTS.size)
        MethodCostUtil.EVENTS.clear()
        try { klass.getMethod("compute", String::class.java).invoke(null, "bad"); fail("expected NumberFormatException") }
        catch (failure: InvocationTargetException) { assertTrue(failure.cause is NumberFormatException) }
        assertEquals(2, klass.getField("finallyCalls").get(null))
        assertEquals(2, MethodCostUtil.EVENTS.size)
    }

    @Test fun stackGraphFollowsInheritedInterfaceDefaultImplementations() {
        val types = listOf(DispatchSubject::class.java, DispatchSubject.DefaultParent::class.java,
            DispatchSubject.DefaultChild::class.java, DispatchSubject.DefaultWorker::class.java)
        val input = types.associate { it.name.replace('.', '/') to bytes(it) }
        val options = mapOf("DOKIT_METHOD_SWITCH" to "true", "DOKIT_METHOD_STRATEGY" to "0", "DOKIT_METHOD_STACK_LEVEL" to "2",
            "stack.entries" to "example.DispatchSubject.defaultEntry")
        val pipeline = DoKitBytecodePipeline(state(options, runtime.keys + input.keys), runtime + input)
        pipeline.transform(input).values.forEach(::verify)
        assertEquals(2, pipeline.selectedMethods.size)
        assertTrue(pipeline.selectedMethods.keys.any { it.owner == "example/DispatchSubject$" + "DefaultParent" && it.name == "inheritedDefault" })
    }
    @Test fun activitySuperConstructorTracksThisThroughAliasesAndLocalZeroReuse() {
        for (alias in listOf(false, true)) {
            val owner = if (alias) "example/AliasActivity" else "example/LocalZeroActivity"
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "android/app/Activity", null)
            writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode(); visitVarInsn(Opcodes.ALOAD, 0)
                if (alias) { visitVarInsn(Opcodes.ASTORE, 1); visitVarInsn(Opcodes.ALOAD, 1) }
                visitMethodInsn(Opcodes.INVOKESPECIAL, "android/app/Activity", "<init>", "()V", false)
                visitTypeInsn(Opcodes.NEW, "android/app/Activity"); visitVarInsn(Opcodes.ASTORE, 0)
                visitVarInsn(Opcodes.ALOAD, 0); visitMethodInsn(Opcodes.INVOKESPECIAL, "android/app/Activity", "<init>", "()V", false)
                visitInsn(Opcodes.RETURN); visitMaxs(0, 0); visitEnd()
            }; writer.visitEnd()
            val original = writer.toByteArray()
            load(owner.replace('/', '.'), original).getConstructor().newInstance()
            val input = mapOf(owner to original)
            val available = runtime + mapOf("android/app/Activity" to bytes(android.app.Activity::class.java),
                "com/didichuxing/doraemonkit/aop/mc/DoKitProxyActivity" to bytes(DoKitProxyActivity::class.java)) + input
            val transformed = DoKitBytecodePipeline(state(available = available.keys), available).transform(input).getValue(owner)
            val calls = node(transformed).methods.single().instructions.filterIsInstance<MethodInsnNode>()
            assertEquals(1, calls.count { it.owner == "com/didichuxing/doraemonkit/aop/mc/DoKitProxyActivity" })
            assertEquals(1, calls.count { it.owner == "android/app/Activity" })
            verify(transformed)
            val klass = load(owner.replace('/', '.'), transformed)
            klass.getConstructor().newInstance()
            assertEquals(DoKitProxyActivity::class.java, klass.superclass)
        }
    }

}
