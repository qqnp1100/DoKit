package com.didichuxing.doraemonkit.aop

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class InvocationTrackerTest {
    @Test fun recursiveCallsAndOverloadsKeepIndependentStartValues() {
        val tracker = InvocationTracker<Long>()
        val receiver = Any()
        tracker.enter("Example&call&()V", receiver) { 10L }
        tracker.enter("Example&call&()V", receiver) { 20L }
        tracker.enter("Example&call&(I)V", receiver) { 30L }
        assertEquals(30L, tracker.exit("Example&call&(I)V", receiver)!!.value)
        assertEquals(20L, tracker.exit("Example&call&()V", receiver)!!.value)
        assertEquals(10L, tracker.exit("Example&call&()V", receiver)!!.value)
        assertNull(tracker.exit("Example&call&()V", receiver))
    }

    @Test fun receiverIdentityDoesNotUseUserEquals() {
        data class Receiver(val id: Int)
        val tracker = InvocationTracker<Int>()
        val first = Receiver(1)
        val equalButDifferent = Receiver(1)
        tracker.enter("call", first) { 42 }
        assertNull(tracker.exit("call", equalButDifferent))
        assertEquals(42, tracker.exit("call", first)!!.value)
    }

    @Test fun concurrentCallsOnOneReceiverDoNotConsumeEachOthersTimers() {
        val tracker = InvocationTracker<Int>()
        val receiver = Any()
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val results = arrayOf(AtomicReference<Int>(), AtomicReference<Int>())
        val failure = AtomicReference<Throwable>()
        val workers = (0..1).map { index -> Thread {
            try {
                tracker.enter("sameMethod", receiver) { index }
                ready.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                results[index].set(tracker.exit("sameMethod", receiver)!!.value)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            }
        }.apply { start() } }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            workers.forEach { it.join(5000) }
        }
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(0, results[0].get())
        assertEquals(1, results[1].get())
    }
}
