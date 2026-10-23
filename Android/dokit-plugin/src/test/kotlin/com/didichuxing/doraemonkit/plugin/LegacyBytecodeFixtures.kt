package com.didichuxing.doraemonkit.plugin

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes

internal object LegacyBytecodeFixtures {
    /** Java 5 style try/finally, including both normal and exceptional JSR calls. */
    fun jsrFinally(): ByteArray {
        val owner = "example/LegacyFinally"
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        writer.visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "finallyCalls", "I", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "compute", "(Ljava/lang/String;)I", null, null).apply {
            val start = Label(); val end = Label(); val handler = Label(); val subroutine = Label()
            visitCode()
            visitTryCatchBlock(start, end, handler, null)
            visitLabel(start)
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt", "(Ljava/lang/String;)I", false)
            visitVarInsn(Opcodes.ISTORE, 1)
            visitLabel(end)
            visitJumpInsn(Opcodes.JSR, subroutine)
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitLabel(handler)
            visitVarInsn(Opcodes.ASTORE, 2)
            visitJumpInsn(Opcodes.JSR, subroutine)
            visitVarInsn(Opcodes.ALOAD, 2)
            visitInsn(Opcodes.ATHROW)
            visitLabel(subroutine)
            visitVarInsn(Opcodes.ASTORE, 3)
            visitFieldInsn(Opcodes.GETSTATIC, owner, "finallyCalls", "I")
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitFieldInsn(Opcodes.PUTSTATIC, owner, "finallyCalls", "I")
            visitVarInsn(Opcodes.RET, 3)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }
}
