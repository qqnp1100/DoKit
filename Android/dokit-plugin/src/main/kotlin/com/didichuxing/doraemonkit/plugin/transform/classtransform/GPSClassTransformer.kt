package com.didichuxing.doraemonkit.plugin.transform.classtransform

import com.didichuxing.doraemonkit.plugin.DoKitState
import com.didichuxing.doraemonkit.plugin.extension.DoKitExtension
import com.didichuxing.doraemonkit.plugin.println

import com.didichuxing.doraemonkit.plugin.asIterable
import com.didichuxing.doraemonkit.plugin.TransformContext
import com.didichuxing.doraemonkit.plugin.className

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode


/**
 * didi Create on 2023/3/21 .
 *
 * Copyright (c) 2023/3/21 by didiglobal.com.
 *
 * @author <a href="realonlyone@126.com">zhangjun</a>
 * @version 1.0
 * @Date 2023/3/21 3:06 下午
 * @Description 系统定位hooks
 */

class GPSClassTransformer(state: DoKitState) : AbsClassTransformer(state) {

    override fun transform(dokit: DoKitExtension, context: TransformContext, klass: ClassNode): ClassNode {
        val className = klass.className

        //gps字节码操作
        if (dokit.gpsEnable && dokit.gps.local && state.DOKIT_GPS_MOCK_INCLUDE) {
            //系统 gpsStatus hook
            klass.methods.forEach { method ->
                method.instructions?.iterator()?.asIterable()
                    ?.filterIsInstance(MethodInsnNode::class.java)?.filter {
                        it.opcode == Opcodes.INVOKEVIRTUAL &&
                            it.owner == "android/location/LocationManager" &&
                            it.name == "getGpsStatus" &&
                            it.desc == "(Landroid/location/GpsStatus;)Landroid/location/GpsStatus;" && (it.next as? MethodInsnNode)?.owner != "com/didichuxing/doraemonkit/gps_mock/location/GpsStatusUtil"
                    }?.forEach {
                        state.log("${context.variantName}->hook LocationManager#getGpsStatus method  succeed in : ${className}_${method.name}_${method.desc}")
                        method.instructions.insert(
                            it,
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "com/didichuxing/doraemonkit/gps_mock/location/GpsStatusUtil",
                                "wrap",
                                "(Landroid/location/GpsStatus;)Landroid/location/GpsStatus;",
                                false
                            )
                        )
                    }
            }
        }
        return klass
    }


}
