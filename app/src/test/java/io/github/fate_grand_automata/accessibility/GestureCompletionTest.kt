package io.github.fate_grand_automata.accessibility

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GestureCompletionTest {
    @Test
    fun `completed gestures return and duplicate callbacks are harmless`() = runBlocking {
        awaitGestureCompletion(100) { completed, cancelled ->
            completed()
            completed()
            cancelled()
            true
        }
    }

    @Test
    fun `rejected dispatch exits immediately even without a callback`() = runBlocking {
        val failure = assertFailsWith<AccessibilityGestureException> {
            awaitGestureCompletion(10_000) { _, _ -> false }
        }
        assertEquals(GestureFailure.DispatchRejected, failure.failure)
    }

    @Test
    fun `cancelled dispatch cannot silently continue a click sequence`() = runBlocking {
        val failure = assertFailsWith<AccessibilityGestureException> {
            awaitGestureCompletion(100) { _, cancelled -> cancelled(); true }
        }
        assertEquals(GestureFailure.Cancelled, failure.failure)
    }

    @Test
    fun `missing callbacks time out and a late callback does not resume the run`() = runBlocking {
        var lateCallback: () -> Unit = {}
        val tracker = GestureDrainTracker()
        var drainNotifications = 0
        tracker.observeQuiescence { drainNotifications++ }
        val failure = assertFailsWith<AccessibilityGestureException> {
            awaitGestureCompletion(10, tracker) { completed, _ -> lateCallback = completed; true }
        }
        assertEquals(GestureFailure.CallbackTimedOut, failure.failure)
        assertFalse(tracker.isQuiescent())
        assertEquals(1, drainNotifications)
        lateCallback()
        assertTrue(tracker.isQuiescent())
        assertEquals(2, drainNotifications)
        lateCallback()
        assertEquals(2, drainNotifications)
    }

    @Test
    fun `platform permission exceptions preserve their type`() = runBlocking {
        val tracker = GestureDrainTracker()
        assertFailsWith<SecurityException> {
            awaitGestureCompletion(100, tracker) { _, _ -> throw SecurityException("Permission revoked") }
        }
        assertFalse(tracker.isQuiescent())
    }

    @Test
    fun `completed continued strokes retain the pointer until final acknowledged release`() = runBlocking {
        val tracker = GestureDrainTracker()
        awaitGestureCompletion(100, tracker, willContinue = true) { completed, _ -> completed(); true }
        assertFalse(tracker.isQuiescent())
        awaitGestureCompletion(100, tracker, isContinuation = true, willContinue = true) { completed, _ -> completed(); true }
        assertFalse(tracker.isQuiescent())
        awaitGestureCompletion(100, tracker, isContinuation = true) { completed, _ -> completed(); true }
        assertTrue(tracker.isQuiescent())
    }

    @Test
    fun `rejected continuation leaves the previous pointer release unconfirmed`() = runBlocking {
        val tracker = GestureDrainTracker()
        awaitGestureCompletion(100, tracker, willContinue = true) { completed, _ -> completed(); true }
        assertFailsWith<AccessibilityGestureException> {
            awaitGestureCompletion(100, tracker, isContinuation = true) { _, _ -> false }
        }
        assertFalse(tracker.isQuiescent())
    }

    @Test
    fun `actual Android cancellation confirms continued input has drained`() = runBlocking {
        val tracker = GestureDrainTracker()
        awaitGestureCompletion(100, tracker, willContinue = true) { completed, _ -> completed(); true }
        val failure = assertFailsWith<AccessibilityGestureException> {
            awaitGestureCompletion(100, tracker, isContinuation = true) { _, cancelled -> cancelled(); true }
        }
        assertEquals(GestureFailure.Cancelled, failure.failure)
        assertTrue(tracker.isQuiescent())
    }

    @Test
    fun `late completion of a timed out continued stroke does not prove pointer release`() = runBlocking {
        val tracker = GestureDrainTracker()
        var lateCallback: () -> Unit = {}
        assertFailsWith<AccessibilityGestureException> {
            awaitGestureCompletion(10, tracker, willContinue = true) { completed, _ -> lateCallback = completed; true }
        }
        assertFalse(tracker.isQuiescent())
        lateCallback()
        assertFalse(tracker.isQuiescent())
    }
}
