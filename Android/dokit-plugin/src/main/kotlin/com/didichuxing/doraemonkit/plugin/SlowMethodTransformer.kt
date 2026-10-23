package com.didichuxing.doraemonkit.plugin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Handle
import org.objectweb.asm.tree.*

/** Build the complete graph before inserting hooks: traversal order cannot affect reachability. */
internal class SlowMethodTransformer(private val state: DoKitState, private val hierarchy: ClassHierarchy? = null) {
    data class MethodKey(val owner: String, val name: String, val descriptor: String) : Comparable<MethodKey> {
        override fun compareTo(other: MethodKey) = toString().compareTo(other.toString())
        override fun toString() = "${owner.replace('/', '.')}&$name&$descriptor"
    }
    fun selectedMethods(classes: Map<String, ClassNode>): Map<MethodKey, Int> {
        if (!state.dokitSlowMethodSwitchOpen()) return emptyMap()
        val methods = sortedMapOf<MethodKey, MethodNode>()
        classes.toSortedMap().forEach { (owner, klass) ->
            if (!state.ignorePackageNames(klass.className)) klass.methods.forEach { method ->
                if (method.access and (Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE) == 0 && method.name != "<init>" && method.instructions.size() > 0) {
                    methods[MethodKey(owner, method.name, method.desc)] = method
                }
            }
        }
        fun blacklisted(key: MethodKey, blacklist: Set<String>) = blacklist.any { key.toString().contains(it) || "${key.owner.replace('/', '.')}.${key.name}".contains(it) }
        fun eligible(key: MethodKey, method: MethodNode) = !method.isEmptyMethod() && !method.isSingleMethod() && !method.isGetSetMethod() && !method.isMainMethod(key.owner)
        if (state.SLOW_METHOD_STRATEGY == 1) return methods.filter { (key, method) ->
            state.slowMethodExt.normalMethod.packageNames.any { key.owner.replace('/', '.').contains(it) } &&
                !blacklisted(key, state.slowMethodExt.normalMethod.methodBlacklist) && eligible(key, method)
        }.mapValues { 0 }
        val classHierarchy = hierarchy ?: ClassHierarchy(emptyMap(), classes)
        fun declared(owner: String, name: String, descriptor: String): MethodKey? {
            var current: String? = owner
            val visited = mutableSetOf<String>()
            val interfaces = ArrayDeque<String>()
            while (current != null && visited.add(current)) {
                val key = MethodKey(current, name, descriptor)
                if (key in methods) return key
                classes[current]?.interfaces?.let(interfaces::addAll)
                current = classes[current]?.superName
            }
            // Default interface methods can be inherited by a subinterface or
            // a concrete class without appearing in its superclass chain.
            while (interfaces.isNotEmpty()) {
                val next = interfaces.removeFirst()
                if (!visited.add(next)) continue
                val key = MethodKey(next, name, descriptor)
                if (key in methods) return key
                classes[next]?.interfaces?.let(interfaces::addAll)
            }
            return null
        }
        val bySignature = methods.keys.groupBy { it.name to it.descriptor }
        fun targets(owner: String, name: String, descriptor: String, virtual: Boolean): List<MethodKey> {
            val result = sortedSetOf<MethodKey>()
            declared(owner, name, descriptor)?.let(result::add)
            if (virtual) bySignature[name to descriptor].orEmpty().filter { candidate ->
                classHierarchy.assignable(owner, candidate.owner) &&
                    methods.getValue(candidate).access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) == 0
            }.forEach(result::add)
            return result.toList()
        }
        val selected = sortedMapOf<MethodKey, Int>()
        methods.keys.filter { key ->
            val fullName = "${key.owner.replace('/', '.')}.${key.name}"
            state.slowMethodExt.stackMethod.enterMethods.any { it == fullName || it == "$fullName${key.descriptor}" } &&
                !blacklisted(key, state.slowMethodExt.stackMethod.methodBlacklist)
        }.forEach { selected[it] = 0 }
        var frontier = selected.keys.toSortedSet()
        for (level in 1 until state.STACK_METHOD_LEVEL) {
            val next = sortedSetOf<MethodKey>()
            frontier.forEach { parent ->
                methods.getValue(parent).instructions.forEach { instruction ->
                    val targets = when (instruction) {
                        is MethodInsnNode -> targets(instruction.owner, instruction.name, instruction.desc,
                            instruction.opcode == Opcodes.INVOKEVIRTUAL || instruction.opcode == Opcodes.INVOKEINTERFACE)
                        is InvokeDynamicInsnNode -> instruction.bsmArgs.filterIsInstance<Handle>().flatMap {
                            targets(it.owner, it.name, it.desc, it.tag == Opcodes.H_INVOKEVIRTUAL || it.tag == Opcodes.H_INVOKEINTERFACE)
                        }
                        else -> emptyList()
                    }
                    targets.filter { target -> target !in selected && target.name != "<init>" && methods[target]?.let {
                        eligible(target, it) && !blacklisted(target, state.slowMethodExt.stackMethod.methodBlacklist)
                    } == true }.forEach(next::add)
                }
            }
            next.forEach { selected[it] = level }
            frontier = next
        }
        return selected
    }
    fun transform(classes: Map<String, ClassNode>): Map<MethodKey, Int> {
        val stack = state.SLOW_METHOD_STRATEGY == 0
        val owner = if (stack) "com/didichuxing/doraemonkit/aop/method_stack/MethodStackUtil" else "com/didichuxing/doraemonkit/aop/MethodCostUtil"
        if (!state.hasClass(owner)) return emptyMap()
        val selected = selectedMethods(classes)
        selected.forEach { (key, level) ->
            val method = classes.getValue(key.owner).methods.single { it.name == key.name && it.desc == key.descriptor }
            // Reapplying DoKit through a library and an application must not insert a second pair.
            if (method.instructions.filterIsInstance<MethodInsnNode>().any { it.owner == owner && it.name.endsWith("CostStart") }) return@forEach
            val start = LabelNode(); val end = LabelNode(); val handler = LabelNode()
            val static = method.access and Opcodes.ACC_STATIC != 0
            method.instructions.insert(hook(owner, key, level, static, true).apply { add(start) })
            method.instructions.toArray().filter { it.opcode in Opcodes.IRETURN..Opcodes.RETURN }.forEach {
                method.instructions.insertBefore(it, hook(owner, key, level, static, false))
            }
            // A catch-all balances implicit exceptions and explicit ATHROW, including try/finally.
            method.instructions.add(end)
            method.instructions.add(handler)
            val exceptionSlot = method.maxLocals++
            method.instructions.add(VarInsnNode(Opcodes.ASTORE, exceptionSlot))
            method.instructions.add(hook(owner, key, level, static, false))
            method.instructions.add(VarInsnNode(Opcodes.ALOAD, exceptionSlot))
            method.instructions.add(InsnNode(Opcodes.ATHROW))
            method.tryCatchBlocks.add(TryCatchBlockNode(start, end, handler, null))
        }
        return selected
    }
    private fun hook(owner: String, key: MethodKey, level: Int, static: Boolean, start: Boolean): InsnList {
        val stack = state.SLOW_METHOD_STRATEGY == 0
        val threshold = if (stack) state.slowMethodExt.stackMethod.thresholdTime else state.slowMethodExt.normalMethod.thresholdTime
        val className = key.owner.replace('/', '.')
        return InsnList().apply {
            add(FieldInsnNode(Opcodes.GETSTATIC, owner, "INSTANCE", "L$owner;"))
            if (stack && start) add(LdcInsnNode(state.STACK_METHOD_LEVEL))
            add(LdcInsnNode(threshold))
            if (stack) { add(LdcInsnNode(level)); add(LdcInsnNode(className)); add(LdcInsnNode(key.name)); add(LdcInsnNode(key.descriptor)) }
            else add(LdcInsnNode("$className&${key.name}&${key.descriptor}"))
            if (!static) add(VarInsnNode(Opcodes.ALOAD, 0))
            val descriptor = if (stack) "(${if (start) "III" else "II"}Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;${if (static) "" else "Ljava/lang/Object;"})V"
                else "(ILjava/lang/String;${if (static) "" else "Ljava/lang/Object;"})V"
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner,
                "recode${if (static) "Static" else "Object"}MethodCost${if (start) "Start" else "End"}", descriptor, false))
        }
    }
}
