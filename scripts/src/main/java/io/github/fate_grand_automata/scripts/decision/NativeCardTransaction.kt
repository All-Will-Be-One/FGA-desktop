package io.github.fate_grand_automata.scripts.decision

/** Executes the exact supplied sequence. No hand inspection, selection policy, retry or effect proof. */
fun deliverNativeCardOrder(order: List<Int>, choose: (Int) -> Unit, afterDelivery: () -> Unit) {
    order.forEach(choose)
    afterDelivery()
}
