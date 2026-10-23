package com.didichuxing.doraemonkit.aop.method_stack

import com.didichuxing.doraemonkit.aop.InvocationTracker

/** Collect a bounded invocation tree independently of Android logging and UI. */
internal class MethodStackRecorder(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Active(val depth: Int?, val limit: Int, val node: MethodInvokNode?)
    private val calls = InvocationTracker<Active>()

    fun enter(
        totalLevel: Int,
        currentLevel: Int,
        className: String?,
        methodName: String,
        descriptor: String?,
        receiver: Any?
    ): Boolean {
        val frame = calls.enter(key(className, methodName, descriptor), receiver) { parent ->
            val parentDepth = parent?.value?.depth
            val depth = if (parentDepth != null) parentDepth + 1 else if (currentLevel == 0) 0 else null
            val limit = if (parentDepth != null) parent.value.limit else totalLevel.coerceAtLeast(1)
            val node = if (depth != null && depth < limit) MethodInvokNode().apply {
                startTimeMillis = clock()
                currentThreadName = Thread.currentThread().name
                this.className = className
                this.methodName = methodName
                this.descriptor = descriptor
                level = depth
            } else null
            Active(depth, limit, node)
        }
        return frame.value.node?.level == 0
    }

    /** Return a completed root; nested calls attach to the actual active caller. */
    fun exit(
        thresholdTime: Int,
        className: String,
        methodName: String,
        descriptor: String?,
        receiver: Any?
    ): MethodInvokNode? {
        val frame = calls.exit(key(className, methodName, descriptor), receiver) ?: return null
        val node = frame.value.node ?: return null
        node.setEndTimeMillis(clock())
        if (node.level == 0) return node
        if (node.getCostTimeMillis() > thresholdTime) {
            frame.parent?.value?.node?.let { parent ->
                node.parent = parent
                parent.addChild(node)
            }
        }
        return null
    }

    private fun key(className: String?, methodName: String, descriptor: String?) =
        "$className&$methodName&${descriptor.orEmpty()}"
}
