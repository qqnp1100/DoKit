package com.didichuxing.doraemonkit.plugin.transform.classtransform

import com.didichuxing.doraemonkit.plugin.DoKitState
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import com.didichuxing.doraemonkit.plugin.extension.SlowMethodExtension
import com.didichuxing.doraemonkit.plugin.formatSuperName
import com.didichuxing.doraemonkit.plugin.println
import com.didichuxing.doraemonkit.plugin.TransformContext
import com.didichuxing.doraemonkit.plugin.className

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.analysis.Analyzer
import org.objectweb.asm.tree.analysis.SourceInterpreter
import org.objectweb.asm.tree.analysis.SourceValue
import org.objectweb.asm.tree.*

/**
 * ================================================
 * 作    者：jint（金台）
 * 版    本：1.0
 * 创建日期：2020/5/14-18:07
 * 描    述：wiki:https://juejin.im/post/5e8d87c4f265da47ad218e6b
 * 修订历史：
 * ================================================
 */
class CommClassTransformer(state: DoKitState) : AbsClassTransformer(state) {

    override fun transform(context: TransformContext, klass: ClassNode): ClassNode {

        val className = klass.className
        val superName = klass.formatSuperName

        //hook Androidx的ComponentActivity
        if (state.hasClass("com/didichuxing/doraemonkit/aop/mc/DoKitProxyActivity") && className != "com.didichuxing.doraemonkit.aop.mc.DoKitProxyActivity" && superName == "android.app.Activity") {
            createComponentActivitySuperActivityImpl(klass)
        }

        return super.transform(context, klass)
    }

    /**
     * 类处理转化实现
     */
    override fun transform(dokit: DoKitExtension, context: TransformContext, klass: ClassNode): ClassNode {
        val className = klass.className

        //查找DoraemonKitReal&pluginConfig方法并插入指定字节码
        if (className == "com.didichuxing.doraemonkit.DoKitReal") {
            //插件配置
            klass.methods?.find {
                it.name == "pluginConfig"
            }.let { methodNode ->
                state.log("${context.variantName}->insert map to the DoKitReal pluginConfig succeed")
                if (methodNode != null && methodNode.instructions.filterIsInstance<MethodInsnNode>().none { it.owner == "com/didichuxing/doraemonkit/aop/DokitPluginConfig" && it.name == "inject" }) {
                    val local = methodNode.maxLocals++
                    methodNode.instructions.insert(createPluginConfigInsnList(dokit.gpsEnable && state.DOKIT_GPS_MOCK_INCLUDE, dokit.networkEnable, dokit.bigImageEnable, local))
                }
            }
        }
        return klass
    }

    /**
     * 创建pluginConfig代码指令
     */
    private fun createPluginConfigInsnList(gpsSwitch: Boolean, networkSwitch: Boolean, bigImgSwitch: Boolean, local: Int): InsnList {
        return with(InsnList()) {
            //new HashMap
            add(TypeInsnNode(Opcodes.NEW, "java/util/HashMap"))
            add(InsnNode(Opcodes.DUP))
            add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false))
            //保存变量
            add(VarInsnNode(Opcodes.ASTORE, local))
            //获取第一个变量
            //put("dokitPluginSwitch",true)
            add(VarInsnNode(Opcodes.ALOAD, local))
            add(LdcInsnNode("dokitPluginSwitch"))
            add(InsnNode(if (state.dokitPluginSwitchOpen()) Opcodes.ICONST_1 else Opcodes.ICONST_0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                )
            )
            add(InsnNode(Opcodes.POP))

            //put("gpsSwitch",true)
            add(VarInsnNode(Opcodes.ALOAD, local))
            add(LdcInsnNode("gpsSwitch"))
            add(InsnNode(if (gpsSwitch) Opcodes.ICONST_1 else Opcodes.ICONST_0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                )
            )
            add(InsnNode(Opcodes.POP))

            //put("networkSwitch",true)
            add(VarInsnNode(Opcodes.ALOAD, local))
            add(LdcInsnNode("networkSwitch"))
            add(InsnNode(if (networkSwitch) Opcodes.ICONST_1 else Opcodes.ICONST_0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                )
            )
            add(InsnNode(Opcodes.POP))

            add(VarInsnNode(Opcodes.ALOAD, local))
            add(LdcInsnNode("bigImgSwitch"))
            add(InsnNode(if (bigImgSwitch) Opcodes.ICONST_1 else Opcodes.ICONST_0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                )
            )
            add(InsnNode(Opcodes.POP))

            add(VarInsnNode(Opcodes.ALOAD, local))
            add(LdcInsnNode("methodSwitch"))
            add(InsnNode(if (state.dokitSlowMethodSwitchOpen()) Opcodes.ICONST_1 else Opcodes.ICONST_0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Boolean",
                    "valueOf",
                    "(Z)Ljava/lang/Boolean;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                )
            )
            add(InsnNode(Opcodes.POP))


            //put("methodStrategy",0)
            add(VarInsnNode(Opcodes.ALOAD, local))
            add(LdcInsnNode("methodStrategy"))
            add(InsnNode(if (state.SLOW_METHOD_STRATEGY == SlowMethodExtension.STRATEGY_STACK) Opcodes.ICONST_0 else Opcodes.ICONST_1))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Integer",
                    "valueOf",
                    "(I)Ljava/lang/Integer;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEINTERFACE,
                    "java/util/Map",
                    "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                )
            )
            add(InsnNode(Opcodes.POP))

            //将HashMap注入到DokitPluginConfig中
            add(VarInsnNode(Opcodes.ALOAD, local))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/DokitPluginConfig",
                    "inject",
                    "(Ljava/util/Map;)V",
                    false
                )
            )
            if (state.hasClass("com/didichuxing/doraemonkit/kit/core/DoKitManager")) {
                val web = state.extension.webView
                for ((field, value) in listOf("H5_JS_INJECT" to web.network,
                    "H5_VCONSOLE_INJECT" to web.vConsole, "H5_DOKIT_MC_INJECT" to web.dokitWeb)) {
                    add(InsnNode(if (state.extension.webViewEnable && value) Opcodes.ICONST_1 else Opcodes.ICONST_0))
                    add(FieldInsnNode(Opcodes.PUTSTATIC, "com/didichuxing/doraemonkit/kit/core/DoKitManager", field, "Z"))
                }
            }
            this
        }
    }

    /**
     * 重置ComponentActivity的父类
     */
    private fun createComponentActivitySuperActivityImpl(klass: ClassNode) {
        // Constructors must invoke the new direct superclass as well.
        klass.methods.filter { it.name == "<init>" }.forEach { method ->
            val thisOrigin = LabelNode()
            val interpreter = object : SourceInterpreter(Opcodes.ASM9) {
                override fun newParameterValue(isInstanceMethod: Boolean, local: Int, type: Type): SourceValue =
                    if (isInstanceMethod && local == 0) SourceValue(1, thisOrigin)
                    else super.newParameterValue(isInstanceMethod, local, type)

                // Preserve value origins through loads/stores/dup. Local zero
                // can be reused for a NEW object after this is initialized.
                override fun copyOperation(instruction: AbstractInsnNode, value: SourceValue): SourceValue = value
            }
            val frames = Analyzer(interpreter).analyze(klass.name, method)
            method.instructions.toArray().forEachIndexed { index, instruction ->
                if (instruction is MethodInsnNode && instruction.opcode == Opcodes.INVOKESPECIAL &&
                    instruction.owner == "android/app/Activity" && instruction.name == "<init>") {
                    val frame = frames[index] ?: return@forEachIndexed
                    val receiver = frame.getStack(frame.stackSize - Type.getArgumentTypes(instruction.desc).size - 1)
                    // Follow the original this value through aliases; do not
                    // confuse another allocation stored in slot zero with it.
                    if (receiver.insns.size == 1 && thisOrigin in receiver.insns) {
                        instruction.owner = "com/didichuxing/doraemonkit/aop/mc/DoKitProxyActivity"
                    }
                }
            }
        }
        /**
         * 修改继承的父类
         */
        klass.superName = "com/didichuxing/doraemonkit/aop/mc/DoKitProxyActivity"
    }

}
