package com.didichuxing.doraemonkit.plugin.transform.classtransform

import com.didichuxing.doraemonkit.plugin.DoKitState
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import com.didichuxing.doraemonkit.plugin.formatSuperName
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
 * @Description WebView hooks
 */

class WebViewClassTransformer(state: DoKitState) : AbsClassTransformer(state) {

    override fun transform(dokit: DoKitExtension, context: TransformContext, klass: ClassNode): ClassNode {

        val className = klass.className
        val superName = klass.formatSuperName

        //网络 OkHttp&didi platform aop
        if (dokit.webViewEnable) {
            //webView 字节码操作
            if (dokit.webView.network || dokit.webView.dokitWeb || dokit.webView.vConsole) {
                //普通的webview
                klass.methods.forEach { method ->
                    method.instructions?.iterator()?.asIterable()
                        ?.filterIsInstance(MethodInsnNode::class.java)?.filter {
                            if ("loadUrl".equals(it.name)) {
                                state.log("hook loadUrl() all ${className} ^${superName}^${it.owner} :: ${it.name} , ${it.desc} ,${it.opcode}")
                            }
                            (it.opcode == Opcodes.INVOKEVIRTUAL || it.opcode == Opcodes.INVOKESPECIAL)
                                && it.name == "loadUrl"
                                && (it.desc == "(Ljava/lang/String;)V" || it.desc == "(Ljava/lang/String;Ljava/util/Map;)V")
                                && isWebViewOwnerNameMatched(it.owner)
                                && (it.previous as? MethodInsnNode)?.owner != "com/didichuxing/doraemonkit/aop/WebViewHook"
                                && (it.previous?.previous as? MethodInsnNode)?.owner != "com/didichuxing/doraemonkit/aop/WebViewHook"
                        }?.forEach {
                            state.log("${context.variantName}->hook WebView#loadurl method  succeed in :  ${className}_${method.name}_${method.desc} | ${it.owner}")
                            if (it.desc == "(Ljava/lang/String;)V") {
                                method.instructions.insertBefore(it, createWebViewInsnList())
                            } else {
                                method.instructions.insertBefore(it, createWebViewInsnList(method))
                            }
                        }
                }
            }
        }
        return klass
    }


    private fun isWebViewOwnerNameMatched(ownerName: String): Boolean {
        return ownerName == "android/webkit/WebView" ||
            ownerName == "com/tencent/smtt/sdk/WebView" ||
            ownerName.contentEquals("WebView") ||
            ownerName == state.WEBVIEW_CLASS_NAME
    }


    /**
     * 创建webView函数指令集
     * 参考:https://www.jianshu.com/p/7d623f441bed
     */
    private fun createWebViewInsnList(): InsnList {
        return with(InsnList()) {
            //复制栈顶的2个指令 指令集变为 比如 aload 2 aload0 / aload 2 aload0
            add(InsnNode(Opcodes.DUP2))
            //抛出最上面的指令 指令集变为 aload 2 aload0 / aload 2  其中 aload 2即为我们所需要的对象
            add(InsnNode(Opcodes.POP))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/WebViewHook",
                    "inject",
                    "(Ljava/lang/Object;)V",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/WebViewHook",
                    "getSafeUrl",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false
                )
            )
            this
        }
    }

    /**
     * 创建webView函数指令集 (多参数，先存参数然后取出)
     * 参考:https://www.jianshu.com/p/7d623f441bed
     */
    private fun createWebViewInsnList(method: MethodNode): InsnList {
        val size = method.maxLocals
        method.maxLocals += 2
        return with(InsnList()) {
            add(VarInsnNode(Opcodes.ASTORE, size + 1))
            add(VarInsnNode(Opcodes.ASTORE, size))
            add(InsnNode(Opcodes.DUP))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/WebViewHook",
                    "inject",
                    "(Ljava/lang/Object;)V",
                    false
                )
            )
            add(VarInsnNode(Opcodes.ALOAD, size))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/WebViewHook",
                    "getSafeUrl",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    false
                )
            )
            add(VarInsnNode(Opcodes.ALOAD, size + 1))
            this
        }
    }
}
