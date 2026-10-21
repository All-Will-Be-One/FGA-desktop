package io.github.fate_grand_automata.scripts.decision

import kotlin.test.*
import io.github.lib_automata.ScriptAbortException

class NativeCardTransactionTest {
    @Test fun `executes only exact received order and returns without an effect detector`() {
        val delivered = mutableListOf<Int>()
        var ended = false
        deliverNativeCardOrder(listOf(5, 3, 4), { delivered += it }, { ended = true })
        assertEquals(listOf(5, 3, 4), delivered)
        assertTrue(ended)
    }
    @Test fun `actual native cancellation interrupts sequence without retry`() {
        val delivered = mutableListOf<Int>()
        assertFailsWith<ScriptAbortException> {
            deliverNativeCardOrder(listOf(6, 1, 2), { id ->
                delivered += id
                if (id == 1) throw ScriptAbortException()
            }, { error("Cannot report completion after native cancellation") })
        }
        assertEquals(listOf(6, 1), delivered)
    }
}
