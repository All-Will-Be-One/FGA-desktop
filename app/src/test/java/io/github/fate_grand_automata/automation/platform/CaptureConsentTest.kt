package io.github.fate_grand_automata.automation.platform

import kotlin.test.*

class CaptureConsentTest {
    @Test fun repeatedPrepareHasOnlyOnePendingPermissionRequest() {
        val pending = CaptureConsent().begin()
        assertTrue(pending.pending)
        assertSame(pending, pending.begin())
    }
    @Test fun closeRejectsLateSuccessfulResultAndDoesNotReopen() {
        val stopped = CaptureConsent().begin().close()
        assertTrue(stopped.pending) // The system dialog drains normally.
        assertFalse(stopped.acceptResult)
        val drained = stopped.finish(true)
        assertFalse(drained.pending)
        assertTrue(drained.closed)
        assertFalse(drained.acceptResult)
    }
    @Test fun cancelSuppressesAutoStartUntilAnotherExplicitRequest() {
        val canceled = CaptureConsent().begin().finish(false)
        assertTrue(canceled.closed)
        val retried = canceled.begin()
        assertFalse(retried.closed)
        assertTrue(retried.pending)
        assertTrue(retried.acceptResult)
    }
}
