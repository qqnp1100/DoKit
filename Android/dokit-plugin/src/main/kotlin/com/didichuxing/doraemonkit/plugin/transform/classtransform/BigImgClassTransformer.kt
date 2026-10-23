package com.didichuxing.doraemonkit.plugin.transform.classtransform

import com.didichuxing.doraemonkit.plugin.DoKitState
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import com.didichuxing.doraemonkit.plugin.getMethodExitInsnNodes
import com.didichuxing.doraemonkit.plugin.println
import com.didichuxing.doraemonkit.plugin.TransformContext
import com.didichuxing.doraemonkit.plugin.className

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode

/**
 * ================================================
 * 作    者：jint（金台）
 * 版    本：1.0
 * 创建日期：2020/5/14-18:07
 * 描    述：wiki:https://juejin.im/post/5e8d87c4f265da47ad218e6b
 * 修订历史：
 * ================================================
 */
class BigImgClassTransformer(state: DoKitState) : AbsClassTransformer(state) {


    override fun transform(dokit: DoKitExtension, context: TransformContext, klass: ClassNode): ClassNode {
        if (!dokit.bigImageEnable) {
            return klass
        }

        val className = klass.className
        if (klass.methods.any { method -> method.instructions.filterIsInstance<MethodInsnNode>().any { it.owner.startsWith("com/didichuxing/doraemonkit/aop/bigimg/") } }) return klass
        //glide
        if (dokit.bigImage.glide && className == "com.bumptech.glide.request.SingleRequest") {
            klass.methods.find { methodNode ->
                (methodNode.name == "init" || methodNode.name == "<init>") && methodNode.desc.endsWith(")V")
            }.let { methodNode ->
                //函数结束的地方插入
                methodNode?.instructions?.getMethodExitInsnNodes()?.filter { it.opcode == Opcodes.RETURN }?.forEach {
                    state.log("${context.variantName}->hook glide  succeed: ${className}_${methodNode.name}_${methodNode.desc}")
                    methodNode.instructions?.insertBefore(it, createGlideInsnList())
                }
            }
        }

        //picasso
        if (dokit.bigImage.picasso && className == "com.squareup.picasso.Request") {
            klass.methods.find { methodNode ->
                methodNode.name == "<init>" && methodNode.desc != null
            }.let { methodNode ->
                //函数结束的地方插入
                methodNode?.instructions?.getMethodExitInsnNodes()?.filter { it.opcode == Opcodes.RETURN }?.forEach {
                    state.log("${context.variantName}->hook picasso  succeed: ${className}_${methodNode.name}_${methodNode.desc}")
                    methodNode.instructions?.insertBefore(it, createPicassoInsnList())
                }
            }
        }

        //Fresco
        if (dokit.bigImage.fresco && className == "com.facebook.imagepipeline.request.ImageRequest") {
            klass.methods.find { methodNode ->
                methodNode.name == "<init>" && methodNode.desc.startsWith("(Lcom/facebook/imagepipeline/request/ImageRequestBuilder;")
            }.let { methodNode ->
                state.log("${context.variantName}->hook Fresco succeed: ${className}_${methodNode?.name}_${methodNode?.desc}")
                //函数开始的地方插入
                methodNode?.instructions?.insert(createFrescoInsnList())
            }
        }

        //ImageLoader
        if (dokit.bigImage.imageLoader && className == "com.nostra13.universalimageloader.core.ImageLoadingInfo") {
            klass.methods.find { methodNode ->
                methodNode.name == "<init>" && org.objectweb.asm.Type.getArgumentTypes(methodNode.desc).let { arguments ->
                    arguments.size >= 6 && arguments.take(5).sumOf { it.size } == 5 &&
                        arguments[5].descriptor == "Lcom/nostra13/universalimageloader/core/listener/ImageLoadingListener;"
                }
            }.let { methodNode ->
                state.log("${context.variantName}->hook ImageLoader  succeed: ${className}_${methodNode?.name}_${methodNode?.desc}")
                methodNode?.instructions?.insert(createImageLoaderInsnList())
            }
        }

        //Coil
        if (dokit.bigImage.coil && className == "coil.request.ImageRequest") {
            state.log("hook Coil Start")
            klass.methods.find { methodNode ->
                methodNode.name == "<init>" && methodNode.desc != null
            }.let { methodNode ->
                //函数结束的地方插入
                methodNode?.instructions?.getMethodExitInsnNodes()?.filter { it.opcode == Opcodes.RETURN }?.forEach {
                    state.log("${context.variantName}->hook Coil  succeed: ${className}_${methodNode.name}_${methodNode.desc}")
                    methodNode.instructions?.insertBefore(it, createCoilInsnList())
                }
            }
        }
        return klass
    }

    /**
     * 创建Glide Aop代码指令
     */
    private fun createGlideInsnList(): InsnList {
        return with(InsnList()) {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/bigimg/glide/GlideHook",
                    "proxy",
                    "(Ljava/lang/Object;)V",
                    false
                )
            )
            this
        }
    }

    /**
     * 创建Picasso Aop代码指令
     */
    private fun createPicassoInsnList(): InsnList {
        return with(InsnList()) {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/bigimg/picasso/PicassoHook",
                    "proxy",
                    "(Ljava/lang/Object;)V",
                    false
                )
            )
            this
        }

    }

    /**
     * 创建Fresco Aop代码指令
     */
    private fun createFrescoInsnList(): InsnList {
        return with(InsnList()) {
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/facebook/imagepipeline/request/ImageRequestBuilder",
                    "getSourceUri",
                    "()Landroid/net/Uri;",
                    false
                )
            )
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/facebook/imagepipeline/request/ImageRequestBuilder",
                    "getPostprocessor",
                    "()Lcom/facebook/imagepipeline/request/Postprocessor;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/bigimg/fresco/FrescoHook",
                    "proxy",
                    "(Landroid/net/Uri;Lcom/facebook/imagepipeline/request/Postprocessor;)Lcom/facebook/imagepipeline/request/Postprocessor;",
                    false
                )
            )
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/facebook/imagepipeline/request/ImageRequestBuilder",
                    "setPostprocessor",
                    "(Lcom/facebook/imagepipeline/request/Postprocessor;)Lcom/facebook/imagepipeline/request/ImageRequestBuilder;",
                    false
                )
            )
            add(InsnNode(Opcodes.POP))
            this
        }

    }

    /**
     * 创建ImageLoader Aop代码指令
     */
    private fun createImageLoaderInsnList(): InsnList {
        return with(InsnList()) {
            add(VarInsnNode(Opcodes.ALOAD, 6))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/bigimg/imageloader/ImageLoaderHook",
                    "proxy",
                    "(Lcom/nostra13/universalimageloader/core/listener/ImageLoadingListener;)Lcom/nostra13/universalimageloader/core/listener/ImageLoadingListener;",
                    false
                )
            )
            add(VarInsnNode(Opcodes.ASTORE, 6))
            this
        }
    }

    private fun createCoilInsnList(): InsnList {
        return with(InsnList()) {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/aop/bigimg/coil/CoilHook",
                    "proxy",
                    "(Ljava/lang/Object;)V",
                    false
                )
            )
            this
        }
    }
}
