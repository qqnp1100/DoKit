package com.didichuxing.doraemonkit.plugin

import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.*

fun MethodNode.isGetSetMethod(): Boolean {
    var ignoreCount = 0
    val iterator = instructions.iterator()
    while (iterator.hasNext()) {
        val insnNode = iterator.next()
        val opcode = insnNode.opcode
        if (-1 == opcode) {
            continue
        }
        if (opcode != GETFIELD && opcode != GETSTATIC && opcode != H_GETFIELD && opcode != H_GETSTATIC && opcode != RETURN && opcode != ARETURN && opcode != DRETURN && opcode != FRETURN && opcode != LRETURN && opcode != IRETURN && opcode != PUTFIELD && opcode != PUTSTATIC && opcode != H_PUTFIELD && opcode != H_PUTSTATIC && opcode > SALOAD) {
            if (name.equals("<init>") && opcode == INVOKESPECIAL) {
                ignoreCount++
                if (ignoreCount > 1) {
                    return false
                }
                continue
            }
            return false
        }
    }
    return true
}

fun MethodNode.isSingleMethod(): Boolean {
    val iterator = instructions.iterator()
    while (iterator.hasNext()) {
        val insnNode = iterator.next()
        val opcode = insnNode.opcode
        if (-1 == opcode) {
            continue
        } else if (INVOKEVIRTUAL <= opcode && opcode <= INVOKEDYNAMIC) {
            return false
        }
    }
    return true
}

fun MethodNode.isEmptyMethod(): Boolean {
    val iterator = instructions.iterator()
    while (iterator.hasNext()) {
        val insnNode = iterator.next()
        val opcode = insnNode.opcode
        return if (-1 == opcode) {
            continue
        } else {
            false
        }
    }
    return true
}

fun MethodNode.isMainMethod(className: String): Boolean {
    if (this.name == "main" && this.desc == "([Ljava/lang/String;)V") {
        "====isMainMethod====$className  ${this.name}   ${this.desc}   ${this.access}".println()
        return true
    }

    return false
}


fun InsnList.getMethodExitInsnNodes(): Sequence<InsnNode>? {
    return this.iterator()?.asSequence()?.filterIsInstance(InsnNode::class.java)?.filter {
        it.opcode == RETURN ||
                it.opcode == IRETURN ||
                it.opcode == FRETURN ||
                it.opcode == ARETURN ||
                it.opcode == LRETURN ||
                it.opcode == DRETURN ||
                it.opcode == ATHROW
    }
}


fun String.println() { /* Detailed matching is recorded by the task report. */ }
val MethodInsnNode.ownerClassName: String get() = owner.replace('/', '.')
val ClassNode.className: String get() = name.replace('/', '.')
val ClassNode.formatSuperName: String get() = (superName ?: "").replace('/', '.')

fun <T> Iterator<T>.asIterable(): Iterable<T> = Iterable { this }
