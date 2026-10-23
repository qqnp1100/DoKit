package com.didichuxing.doraemonkit.aop

/** Active calls belong to their executing thread and match receivers by identity. */
internal class InvocationTracker<T> {
    class Frame<T>(
        val key: String,
        val receiver: Any?,
        val value: T,
        val parent: Frame<T>?
    )

    private val frames = ThreadLocal<MutableList<Frame<T>>>()

    fun enter(key: String, receiver: Any?, value: (Frame<T>?) -> T): Frame<T> {
        val stack = frames.get() ?: mutableListOf<Frame<T>>().also(frames::set)
        val parent = stack.lastOrNull()
        return Frame(key, receiver, value(parent), parent).also(stack::add)
    }

    fun exit(key: String, receiver: Any?): Frame<T>? {
        val stack = frames.get() ?: return null
        val index = stack.indexOfLast { it.key == key && it.receiver === receiver }
        if (index < 0) return null
        val frame = stack[index]
        // Recover from an unmatched manual/legacy hook without retaining abandoned calls.
        stack.subList(index, stack.size).clear()
        if (stack.isEmpty()) frames.remove()
        return frame
    }
}
