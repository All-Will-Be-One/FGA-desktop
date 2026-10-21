package io.github.fate_grand_automata.runner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBattleOutcomeTest {
    @Test
    fun `only explicit native defeat and withdrawal observations produce those outcomes`() {
        assertEquals("defeated", nativeBattleOutcome("WithdrawDisabled", 0, 3, 0))
        assertEquals("defeated", nativeBattleOutcome("WithdrawDisabled", 1, 3, 1))
        assertEquals("retreated", nativeBattleOutcome("LimitRuns", 3, 3, 1))
        assertNull(nativeBattleOutcome("Unexpected", 0, 3, 0))
        assertNull(nativeBattleOutcome("Abort", 2, 3, 0))
        assertNull(nativeBattleOutcome("DeadlineExceeded", 2, 3, 0))
    }

    @Test
    fun `target reached requires native completed count to reach the requested target`() {
        assertNull(nativeBattleOutcome("LimitRuns", 0, 3, 0))
        assertEquals("target_reached", nativeBattleOutcome("LimitRuns", 3, 3, 0))
        assertEquals("ap_empty", nativeBattleOutcome("APRanOut", 0, 3, 0))
    }

    @Test
    fun `startup deadline expires at the actual handoff and cannot reset with preparation`() {
        assertFalse(startupDeadlineExpired(1000, 999))
        assertTrue(startupDeadlineExpired(1000, 1000))
        assertTrue(startupDeadlineExpired(1000, 5000))
        assertFalse(startupDeadlineExpired(null, 5000))
    }
}
