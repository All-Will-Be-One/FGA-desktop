package io.github.fate_grand_automata.scripts.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCardTransactionTest {
    @Test
    fun `never sends a fourth potential choice or invents a card fallback`() {
        val delivered = mutableListOf<Pair<Int, Boolean>>()
        deliverNativeCardOrder(listOf(6, 1, 7, 2, 3, 4), { NativeCardPane.Visible },
            { id, final -> delivered += id to final }, {})
        assertEquals(listOf(6 to false, 1 to false, 7 to true), delivered)
    }

    @Test
    fun `missing positive transition after three deliveries fails without fourth input`() {
        val delivered = mutableListOf<Int>()
        assertFailsWith<NativeDecisionException> {
            deliverNativeCardOrder(listOf(6, 1, 2, 3, 4), { NativeCardPane.Visible }, { id, _ -> delivered += id },
                { throw NativeDecisionException("UnsupportedPartialCardSelectionEvidence") })
        }
        assertEquals(listOf(6, 1, 2), delivered)
    }

    @Test
    fun `pane departure stops further input but still requires positive native transition`() {
        var choices = 0
        var transitionChecks = 0
        deliverNativeCardOrder(listOf(1, 2, 3), { if (choices == 1) NativeCardPane.Absent else NativeCardPane.Visible },
            { _, _ -> choices++ }, { transitionChecks++ })
        assertEquals(1, choices)
        assertEquals(1, transitionChecks)
    }

    @Test
    fun `unknown or absent initial hand cannot receive a card input`() {
        for (pane in listOf(NativeCardPane.Absent, NativeCardPane.Unsupported)) {
            var choices = 0
            assertFailsWith<NativeDecisionException> {
                deliverNativeCardOrder(listOf(1, 2, 3), { pane }, { _, _ -> choices++ }, {})
            }
            assertEquals(0, choices)
        }
    }
}
