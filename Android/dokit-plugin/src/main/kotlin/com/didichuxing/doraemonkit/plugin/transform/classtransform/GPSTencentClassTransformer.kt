package com.didichuxing.doraemonkit.plugin.transform.classtransform

import com.didichuxing.doraemonkit.plugin.DoKitState
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import com.didichuxing.doraemonkit.plugin.println
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
 * @Description 腾讯地图 hooks
 */

class GPSTencentClassTransformer(state: DoKitState) : AbsClassTransformer(state) {

    override fun transform(dokit: DoKitExtension, context: TransformContext, klass: ClassNode): ClassNode {
        val className = klass.className
        if (klass.methods.any { method -> method.instructions.filterIsInstance<MethodInsnNode>().any { it.owner.startsWith("com/didichuxing/doraemonkit/gps_mock/map/") } }) return klass

        if (dokit.gpsEnable && dokit.gps.tencent && state.DOKIT_GPS_MOCK_INCLUDE) {
            //插入腾讯地图相关字节码
            if (className == "com.tencent.map.geolocation.TencentLocationManager") {
                //持续定位和单次定位
                klass.methods?.filter {
                    (it.name == "requestSingleFreshLocation" || it.name == "requestLocationUpdates") &&
                        it.access and Opcodes.ACC_STATIC == 0 && org.objectweb.asm.Type.getArgumentTypes(it.desc).let { arguments ->
                            arguments.size >= 2 && arguments[0].size == 1 && arguments[1].descriptor == "Lcom/tencent/map/geolocation/TencentLocationListener;"
                        }
                }?.forEach { methodNode ->
                    state.log("${context.variantName}->hook tencent map  succeed: ${className}_${methodNode?.name}_${methodNode?.desc}")
                    methodNode?.instructions?.insert(createTencentLocationInsnList())
                }

                //反注册监听器
                klass.methods?.find {
                    it.name == "removeUpdates" && it.desc == "(Lcom/tencent/map/geolocation/TencentLocationListener;)V" && it.access and Opcodes.ACC_STATIC == 0
                }.let { methodNode ->
                    state.log("${context.variantName}->hook tencent map  succeed: ${className}_${methodNode?.name}_${methodNode?.desc}")
                    methodNode?.instructions?.insert(
                        createTencentLocationUnRegisterInsnList()
                    )
                }
            }
        }
        return klass
    }

    /**
     * 创建tencent地图代码指令
     */
    private fun createTencentLocationInsnList(): InsnList {
        return with(InsnList()) {
            //在AMapLocationClient的setLocationListener方法之中插入自定义代理回调类
            add(
                TypeInsnNode(
                    Opcodes.NEW,
                    "com/didichuxing/doraemonkit/gps_mock/map/TencentLocationListenerProxy"
                )
            )
            add(InsnNode(Opcodes.DUP))
            //访问第一个参数
            add(VarInsnNode(Opcodes.ALOAD, 2))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "com/didichuxing/doraemonkit/gps_mock/map/TencentLocationListenerProxy",
                    "<init>",
                    "(Lcom/tencent/map/geolocation/TencentLocationListener;)V",
                    false
                )
            )
            //对第二个参数进行重新赋值
            add(VarInsnNode(Opcodes.ASTORE, 2))

            this
        }

    }

    /**
     * 创建Tencent地图UnRegister代码指令
     */
    private fun createTencentLocationUnRegisterInsnList(): InsnList {
        return with(InsnList()) {
            //访问第一个参数
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/didichuxing/doraemonkit/gps_mock/map/ThirdMapLocationListenerUtil",
                    "unRegisterTencentLocationListener",
                    "(Lcom/tencent/map/geolocation/TencentLocationListener;)Lcom/didichuxing/doraemonkit/gps_mock/map/TencentLocationListenerProxy;",
                    false
                )
            )
            add(VarInsnNode(Opcodes.ASTORE, 1))
            this
        }

    }


}
