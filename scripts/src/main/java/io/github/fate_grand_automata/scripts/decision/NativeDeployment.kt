package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.models.ServantTarget

/** Exact external-to-upstream mapping. Null means no target click; no recipient is inferred. */
fun nativeServantTarget(target: Int?): ServantTarget? = when (target) {
    null -> null
    1 -> ServantTarget.A
    2 -> ServantTarget.B
    3 -> ServantTarget.C
    else -> throw NativeDecisionException("Unsupported servant target")
}
