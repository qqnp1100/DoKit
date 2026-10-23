package com.didichuxing.doraemonkit.aop.method_stack

import android.app.Application
import android.util.Log
import com.didichuxing.doraemonkit.util.GsonUtils
import com.didichuxing.doraemonkit.util.LogUtils
import com.didichuxing.doraemonkit.kit.timecounter.TimeCounterManager
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * ================================================
 * 作    者：jint（金台）
 * 版    本：1.0
 * 创建日期：2020/4/22-15:44
 * 描    述：自定义函数入口调用栈的耗时工具类
 * 修订历史：
 * ================================================
 */
public object MethodStackUtil {
    private val recorder = MethodStackRecorder()
    private val lastCompletedRoot = AtomicReference<MethodInvokNode?>()
    private val staticMethodObject = StaticMethodObject()

    fun recodeObjectMethodCostStart(
        totalLevel: Int,
        thresholdTime: Int,
        currentLevel: Int,
        className: String?,
        methodName: String,
        desc: String?,
        classObj: Any?
    ) {
        try {
            val root = recorder.enter(totalLevel, currentLevel, className, methodName, desc, classObj)
            if (root && classObj is Application) {
                if (methodName == "onCreate") TimeCounterManager.get().onAppCreateStart()
                if (methodName == "attachBaseContext") TimeCounterManager.get().onAppAttachBaseContextStart()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recodeObjectMethodCostEnd(
        thresholdTime: Int,
        currentLevel: Int,
        className: String,
        methodName: String,
        desc: String?,
        classObj: Any?
    ) {
        try {
            val root = recorder.exit(thresholdTime, className, methodName, desc, classObj) ?: return
            lastCompletedRoot.set(root)
            toStack(classObj is Application, root)
            if (classObj is Application) {
                if (methodName == "onCreate") TimeCounterManager.get().onAppCreateEnd()
                if (methodName == "attachBaseContext") TimeCounterManager.get().onAppAttachBaseContextEnd()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recodeStaticMethodCostStart(
        totalLevel: Int,
        thresholdTime: Int,
        currentLevel: Int,
        className: String?,
        methodName: String,
        desc: String?
    ) {
        recodeObjectMethodCostStart(
            totalLevel,
            thresholdTime,
            currentLevel,
            className,
            methodName,
            desc,
            staticMethodObject
        )
    }

    fun recodeStaticMethodCostEnd(
        thresholdTime: Int,
        currentLevel: Int,
        className: String,
        methodName: String,
        desc: String?
    ) {
        recodeObjectMethodCostEnd(
            thresholdTime,
            currentLevel,
            className,
            methodName,
            desc,
            staticMethodObject
        )
    }

    private fun jsonTravel(
        methodStackBeans: MutableList<MethodStackBean>?,
        methodInvokNodes: List<MethodInvokNode>?
    ) {
        if (methodInvokNodes == null) {
            return
        }
        for (methodInvokNode in methodInvokNodes) {
            val methodStackBean = MethodStackBean()
            methodStackBean.setCostTime(methodInvokNode.getCostTimeMillis())
            methodStackBean.function = functionName(methodInvokNode)
            methodStackBean.children = ArrayList()
            jsonTravel(methodStackBean.children, methodInvokNode.children)
            methodStackBeans?.add(methodStackBean)
        }
    }

    private fun stackTravel(
        stringBuilder: StringBuilder,
        methodInvokNodes: List<MethodInvokNode>?
    ) {
        if (methodInvokNodes == null) {
            return
        }
        for (methodInvokNode in methodInvokNodes) {
            stringBuilder.append(
                String.format(
                    "%s%s%s%s%s",
                    methodInvokNode.level,
                    SPACE_0,
                    methodInvokNode.getCostTimeMillis().toString() + "ms",
                    getSpaceString(methodInvokNode.level),
                    functionName(methodInvokNode)
                )
            ).append("\n")
            stackTravel(stringBuilder, methodInvokNode.children)
        }
    }

    fun toJson() {
        val methodStackBeans: MutableList<MethodStackBean> = ArrayList()
        for (methodInvokNode in listOfNotNull(lastCompletedRoot.get())) {
            val methodStackBean = MethodStackBean()
            methodStackBean.setCostTime(methodInvokNode.getCostTimeMillis())
            methodStackBean.function = functionName(methodInvokNode)
            methodStackBean.children = ArrayList()
            jsonTravel(methodStackBean.children, methodInvokNode.children)
            methodStackBeans.add(methodStackBean)
        }
        val json = GsonUtils.toJson(methodStackBeans)
        LogUtils.json(json)
    }

    fun toStack(isAppStart: Boolean, methodInvokNode: MethodInvokNode) {
        val stringBuilder = StringBuilder()
        stringBuilder.append("=========DoKit函数调用栈==========").append("\n")
        stringBuilder.append(String.format("%s    %s    %s", "level", "time", "function"))
            .append("\n")
        stringBuilder.append(
            String.format(
                "%s%s%s%s%s",
                methodInvokNode.level,
                SPACE_0,
                methodInvokNode.getCostTimeMillis().toString() + "ms",
                getSpaceString(methodInvokNode.level),
                functionName(methodInvokNode)
            )
        ).append("\n")
        stackTravel(stringBuilder, methodInvokNode.children)
        Log.i(TAG, stringBuilder.toString())
        if (isAppStart && methodInvokNode.level == 0) {
            if (methodInvokNode.methodName == "onCreate") {
                STR_APP_ON_CREATE = stringBuilder.toString()
            }
            if (methodInvokNode.methodName == "attachBaseContext") {
                STR_APP_ATTACH_BASECONTEXT = stringBuilder.toString()
            }
        }
    }

    private fun functionName(node: MethodInvokNode) =
        "${node.className}&${node.methodName}${node.descriptor.orEmpty()}"

    private fun getSpaceString(level: Int): String {
        return when (level) {
            0 -> SPACE_0
            1 -> SPACE_1
            2 -> SPACE_2
            3 -> SPACE_3
            4 -> SPACE_4
            5 -> SPACE_5
            6 -> SPACE_6
            7 -> SPACE_7
            else -> SPACE_0
        }

    }

    private const val TAG = "DOKIT_SLOW_METHOD"


    private const val SPACE_0 = "********"
    private const val SPACE_1 = "*************"
    private const val SPACE_2 = "*****************"
    private const val SPACE_3 = "*********************"
    private const val SPACE_4 = "*************************"
    private const val SPACE_5 = "*****************************"
    private const val SPACE_6 = "*********************************"
    private const val SPACE_7 = "*************************************"

    @Volatile
    @JvmField
    var STR_APP_ON_CREATE: String? = null

    @Volatile
    @JvmField
    var STR_APP_ATTACH_BASECONTEXT: String? = null

}