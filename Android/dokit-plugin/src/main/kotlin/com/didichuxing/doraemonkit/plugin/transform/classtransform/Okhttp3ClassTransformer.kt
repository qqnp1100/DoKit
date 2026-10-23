package com.didichuxing.doraemonkit.plugin.transform.classtransform

import com.didichuxing.doraemonkit.plugin.DoKitState
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import com.didichuxing.doraemonkit.plugin.println

import com.didichuxing.doraemonkit.plugin.asIterable
import com.didichuxing.doraemonkit.plugin.TransformContext
import com.didichuxing.doraemonkit.plugin.className

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*


/**
 * didi Create on 2023/3/21 .
 *
 * Copyright (c) 2023/3/21 by didiglobal.com.
 *
 * @author <a href="realonlyone@126.com">zhangjun</a>
 * @version 1.0
 * @Date 2023/3/21 3:06 下午
 * @Description 用一句话说明文件功能
 */

class Okhttp3ClassTransformer(state: DoKitState) : AbsClassTransformer(state) {

    override fun transform(dokit: DoKitExtension, context: TransformContext, klass: ClassNode): ClassNode {
        val className = klass.className
        if (klass.methods.any { method -> method.instructions.filterIsInstance<MethodInsnNode>().any { it.owner.startsWith("com/didichuxing/doraemonkit/aop/OkHttpHook") } }) return klass

        if (dokit.networkEnable && dokit.network.okHttp) {
            //hook OkhttpClient
            if (className == "okhttp3.OkHttpClient") {
                klass.methods?.find {
                    it.name == "<init>" && it.desc != "()V"
                }.let {
                    state.log("${context.variantName}->hook OkhttpClient  succeed: ${className}_${it?.name}_${it?.desc}")
                    it?.instructions
                        ?.iterator()
                        ?.asIterable()
                        ?.filterIsInstance(FieldInsnNode::class.java)
                        ?.filter { fieldInsnNode ->
                            fieldInsnNode.opcode == Opcodes.PUTFIELD
                                && fieldInsnNode.owner == "okhttp3/OkHttpClient"
                                && fieldInsnNode.name == "networkInterceptors"
                                && fieldInsnNode.desc == "Ljava/util/List;"
                        }?.forEach { fieldInsnNode ->
                            it.instructions.insert(fieldInsnNode, createOkHttpClientInsnList())
                        }
                }
            }
        }
        return klass
    }

    /**
     * 创建OkhttpClient一个数构造函数指令
     */
    private fun createOkHttpClientInsnList(): InsnList {
        return with(InsnList()) {
            //插入application 拦截器
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/OkHttpHook",
                    "addDoKitIntercept",
                    "(Lokhttp3/OkHttpClient;)V",
                    false
                )
            )
            this
        }

    }
}
