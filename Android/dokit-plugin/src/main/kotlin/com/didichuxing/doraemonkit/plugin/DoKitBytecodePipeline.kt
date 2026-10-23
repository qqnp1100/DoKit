package com.didichuxing.doraemonkit.plugin

import com.didichuxing.doraemonkit.plugin.transform.classtransform.*
import org.objectweb.asm.*
import org.objectweb.asm.commons.JSRInlinerAdapter
import org.objectweb.asm.tree.*

class DoKitBytecodePipeline(
    private val state: DoKitState,
    private val classpath: Map<String, ByteArray>,
    private val variant: String = "test"
) {
    val notices = mutableListOf<String>()
    internal var selectedMethods: Map<SlowMethodTransformer.MethodKey, Int> = emptyMap()
        private set
    fun transform(input: Map<String, ByteArray>): Map<String, ByteArray> {
        if (!state.dokitPluginSwitchOpen()) return input
        if (!state.hasClass("com/didichuxing/doraemonkit/DoKitReal")) {
            notices += "DoKit runtime absent; all hooks disabled for this variant"
            return input
        }
        val classes = input.toSortedMap().mapValuesTo(sortedMapOf()) { (_, bytes) ->
            ClassNode(Opcodes.ASM9).also { ClassReader(bytes).accept(it, ClassReader.EXPAND_FRAMES) }
        }
        // Historical Java 5 dependencies may implement finally with JSR/RET.
        // Inline only those methods before any rewriting or frame computation.
        classes.values.forEach { klass ->
            klass.methods.forEachIndexed { index, method ->
                if (method.instructions.any { it.opcode == Opcodes.JSR || it.opcode == Opcodes.RET }) {
                    try {
                        val inlined = MethodNode(Opcodes.ASM9, method.access, method.name, method.desc, method.signature, method.exceptions.toTypedArray())
                        method.accept(JSRInlinerAdapter(inlined, method.access, method.name, method.desc, method.signature, method.exceptions.toTypedArray()))
                        klass.methods[index] = inlined
                        notices += "Inlined legacy JSR/RET ${klass.name}.${method.name}${method.desc}"
                    } catch (failure: RuntimeException) {
                        throw IllegalStateException("DoKit legacy subroutine inlining failed for ${klass.name}.${method.name}${method.desc} (variant $variant)", failure)
                    }
                }
            }
        }
        val context = TransformContext(state, variant)
        val rules = listOf(CommClassTransformer(state), ThirdLibsClassTransformer(state), Okhttp3ClassTransformer(state),
            UrlConnectionTransformer(state), WebViewClassTransformer(state), GPSClassTransformer(state),
            GPSAMapClassTransformer(state), GPSBDClassTransformer(state), GPSTencentClassTransformer(state), BigImgClassTransformer(state))
        classes.entries.forEach { entry ->
            rules.forEach { rule ->
                val original = entry.value
                val candidate = ClassNode(Opcodes.ASM9).also(original::accept)
                rule.transform(context, candidate)
                val addedOwners = hookOwners(candidate) - hookOwners(original)
                val missing = addedOwners.filterNot(state::hasClass)
                if (missing.isEmpty()) entry.setValue(candidate)
                else notices += "${rule.javaClass.simpleName} skipped ${original.name}: missing ${missing.sorted().joinToString()}"
            }
        }
        // Include modified superclass relationships in dispatch analysis and stack-map frames.
        val hierarchy = ClassHierarchy(classpath, classes)
        selectedMethods = SlowMethodTransformer(state, hierarchy).transform(classes)
        return classes.mapValues { (_, klass) ->
            val writer = object : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
                override fun getCommonSuperClass(type1: String, type2: String): String = hierarchy.commonSuper(type1, type2)
            }
            var methodIdentity = "<class>"
            val tracing = object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    methodIdentity = "$name$descriptor"
                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }
            }
            try {
                klass.accept(tracing)
                writer.toByteArray()
            } catch (failure: RuntimeException) {
                throw IllegalStateException("DoKit frame computation/write failed for ${klass.name}.$methodIdentity (variant $variant)", failure)
            }
        }
    }
    private fun hookOwners(klass: ClassNode): Set<String> = buildSet {
        if (klass.superName?.startsWith("com/didichuxing/doraemonkit/") == true) add(klass.superName)
        klass.methods.forEach { method -> method.instructions.forEach { instruction ->
            val owner = when (instruction) { is MethodInsnNode -> instruction.owner; is FieldInsnNode -> instruction.owner; is TypeInsnNode -> instruction.desc; else -> null }
            if (owner?.startsWith("com/didichuxing/doraemonkit/") == true) add(owner)
        } }
    }
}

/** Frame computation reads class headers; Android/application classes are never loaded into Gradle. */
internal class ClassHierarchy(private val bytes: Map<String, ByteArray>, transformed: Map<String, ClassNode>) {
    private data class Header(val superName: String?, val interfaces: List<String>, val isInterface: Boolean)
    private val headers = transformed.mapValuesTo(mutableMapOf()) { (_, klass) -> Header(klass.superName, klass.interfaces, klass.access and Opcodes.ACC_INTERFACE != 0) }
    private fun header(name: String): Header? = headers[name] ?: run {
        val data = bytes[name] ?: ClassLoader.getSystemResourceAsStream("$name.class")?.use { it.readBytes() } ?: return null
        val reader = ClassReader(data)
        Header(reader.superName, reader.interfaces.toList(), reader.access and Opcodes.ACC_INTERFACE != 0).also { headers[name] = it }
    }
    private fun objectName(descriptor: String) = if (descriptor.startsWith("L")) descriptor.substring(1, descriptor.length - 1) else descriptor
    private fun reference(descriptor: String) = descriptor.startsWith("L") || descriptor.startsWith("[")
    fun assignable(target: String, source: String): Boolean {
        if (target == source || target == "java/lang/Object") return true
        if (source.startsWith("[")) {
            if (target == "java/lang/Cloneable" || target == "java/io/Serializable") return true
            if (!target.startsWith("[")) return false
            val a = target.substring(1); val b = source.substring(1)
            return if (reference(a) && reference(b)) assignable(objectName(a), objectName(b)) else a == b
        }
        if (target.startsWith("[")) return false
        val pending = ArrayDeque<String>(); pending.add(source)
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next == target) return true
            if (visited.add(next)) header(next)?.let { h -> h.superName?.let(pending::add); pending.addAll(h.interfaces) }
        }
        return false
    }
    fun commonSuper(a: String, b: String): String {
        if (assignable(a, b)) return a
        if (assignable(b, a)) return b
        if (a.startsWith("[") && b.startsWith("[")) {
            val x = a.substring(1); val y = b.substring(1)
            if (reference(x) && reference(y)) {
                val element = commonSuper(objectName(x), objectName(y))
                return "[" + if (element.startsWith("[")) element else "L$element;"
            }
        }
        if (a.startsWith("[") || b.startsWith("[") || header(a)?.isInterface == true || header(b)?.isInterface == true) return "java/lang/Object"
        var current = header(a)?.superName
        val visited = mutableSetOf<String>()
        while (current != null && visited.add(current)) {
            if (assignable(current, b)) return current
            current = header(current)?.superName
        }
        return "java/lang/Object"
    }
}
