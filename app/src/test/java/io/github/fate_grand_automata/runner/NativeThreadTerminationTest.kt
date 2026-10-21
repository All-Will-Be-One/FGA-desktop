package io.github.fate_grand_automata.runner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeThreadTerminationTest {
    @Test
    fun `logical exit notification does not release a thread still executing its listener tail`() {
        val exitNotified = CountDownLatch(1)
        val returnFromListener = CountDownLatch(1)
        val native = thread {
            exitNotified.countDown()
            check(returnFromListener.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(exitNotified.await(5, TimeUnit.SECONDS))
            assertFalse(awaitNativeThreadTermination(native, 5))
            returnFromListener.countDown()
            assertTrue(awaitNativeThreadTermination(native, 1_000))
        } finally {
            returnFromListener.countDown()
            native.join(1_000)
        }
    }

    @Test
    fun `unknown native thread and self join never claim termination`() {
        assertFalse(awaitNativeThreadTermination(null, 100))
        assertFalse(awaitNativeThreadTermination(Thread.currentThread(), 100))
    }
}
