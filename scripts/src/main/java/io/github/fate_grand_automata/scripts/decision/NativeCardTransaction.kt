package io.github.fate_grand_automata.scripts.decision

enum class NativeCardPane { Visible, Absent, Unsupported }

/** Mechanical safety bound, not card selection policy: never invent a fourth choice or replay. */
fun deliverNativeCardOrder(
    order: List<Int>,
    inspect: () -> NativeCardPane,
    choose: (id: Int, finalPotentialChoice: Boolean) -> Unit,
    awaitNativeTransition: () -> Unit
) {
    for ((index, id) in order.take(3).withIndex()) {
        when (inspect()) {
            NativeCardPane.Visible -> choose(id, index == 2)
            NativeCardPane.Absent -> {
                if (index == 0) throw NativeDecisionException("UnexpectedNativeCardScene")
                awaitNativeTransition()
                return
            }
            NativeCardPane.Unsupported -> throw NativeDecisionException("UnsupportedPartialCardSelectionEvidence")
        }
    }
    awaitNativeTransition()
}
