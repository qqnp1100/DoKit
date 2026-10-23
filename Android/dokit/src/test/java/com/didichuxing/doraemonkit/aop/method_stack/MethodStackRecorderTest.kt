package com.didichuxing.doraemonkit.aop.method_stack

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class MethodStackRecorderTest {
    @Test fun overloadsAndReentryUseActualCallerAndPreserveDescriptors() {
        var time = 0L
        val recorder = MethodStackRecorder { time }
        val receiver = Any()
        assertTrue(recorder.enter(4, 0, "Example", "call", "()V", receiver))
        time++
        assertFalse(recorder.enter(4, 0, "Example", "call", "()V", receiver))
        time++
        assertFalse(recorder.enter(4, 1, "Example", "call", "(I)V", receiver))
        time += 2
        assertNull(recorder.exit(0, "Example", "call", "(I)V", receiver))
        time++
        assertNull(recorder.exit(0, "Example", "call", "()V", receiver))
        time++
        val root = recorder.exit(0, "Example", "call", "()V", receiver)!!
        val child = root.children.single()
        val grandchild = child.children.single()
        assertNotSame(root, child)
        assertSame(root, child.parent)
        assertSame(child, grandchild.parent)
        assertEquals(listOf(0, 1, 2), listOf(root.level, child.level, grandchild.level))
        assertEquals("()V", child.descriptor)
        assertEquals("(I)V", grandchild.descriptor)
        assertEquals(6, root.getCostTimeMillis())
        assertNull(recorder.exit(0, "Example", "call", "()V", receiver))
    }

    @Test fun recursiveDepthIsBoundedAndSuppressedFramesStillUnwind() {
        var time = 0L
        val recorder = MethodStackRecorder { time }
        val receiver = Any()
        repeat(8) { recorder.enter(3, 0, "Example", "recurse", "()V", receiver); time++ }
        repeat(7) { assertNull(recorder.exit(-1, "Example", "recurse", "()V", receiver)); time++ }
        val root = recorder.exit(-1, "Example", "recurse", "()V", receiver)!!
        assertEquals(1, root.children.size)
        assertEquals(1, root.children.single().children.size)
        assertTrue(root.children.single().children.single().children.isEmpty())
        assertTrue(recorder.enter(1, 0, "Example", "next", "()V", receiver))
        assertNotNull(recorder.exit(-1, "Example", "next", "()V", receiver))
    }

    @Test fun unrootedCallsAndThresholdBoundaryDoNotCreateSpuriousChildren() {
        var time = 0L
        val recorder = MethodStackRecorder { time }
        val receiver = Any()
        assertFalse(recorder.enter(4, 2, "Example", "orphan", "()V", receiver))
        assertNull(recorder.exit(0, "Example", "orphan", "()V", receiver))
        recorder.enter(4, 0, "Example", "entry", "()V", receiver)
        recorder.enter(4, 1, "Example", "atThreshold", "()V", receiver)
        time = 5
        recorder.exit(5, "Example", "atThreshold", "()V", receiver)
        recorder.enter(4, 1, "Example", "aboveThreshold", "()V", receiver)
        time = 11
        recorder.exit(5, "Example", "aboveThreshold", "()V", receiver)
        val root = recorder.exit(5, "Example", "entry", "()V", receiver)!!
        assertEquals("aboveThreshold", root.children.single().methodName)
    }

    @Test fun concurrentTreesOnTheSameReceiverStaySeparate() {
        val recorder = MethodStackRecorder { 1L }
        val receiver = Any()
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val roots = ConcurrentLinkedQueue<MethodInvokNode>()
        val failure = AtomicReference<Throwable>()
        val workers = (0..1).map { index -> Thread({
            try {
                recorder.enter(3, 0, "Example", "entry", "()V", receiver)
                recorder.enter(3, 1, "Example", "child", "()V", receiver)
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                recorder.exit(-1, "Example", "child", "()V", receiver)
                roots.add(recorder.exit(-1, "Example", "entry", "()V", receiver)!!)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }, "recorder-$index").apply { start() } }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.forEach { it.join(5000) }
        }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(setOf("recorder-0", "recorder-1"), roots.map { it.currentThreadName }.toSet())
        roots.forEach { root ->
            val child = root.children.single()
            assertEquals(root.currentThreadName, child.currentThreadName)
            assertSame(root, child.parent)
        }
    }
}
