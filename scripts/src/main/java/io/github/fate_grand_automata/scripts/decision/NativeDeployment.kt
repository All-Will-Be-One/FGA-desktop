package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ServantTarget
import io.github.fate_grand_automata.scripts.models.TeamSlot

/** Exact external-to-upstream mapping. Null means no target click; no recipient is inferred. */
fun nativeServantTarget(target: Int?): ServantTarget? = when (target) {
    null -> null
    1 -> ServantTarget.A
    2 -> ServantTarget.B
    3 -> ServantTarget.C
    else -> throw NativeDecisionException("Unsupported servant target")
}

/** Prevent upstream layout remapping from silently substituting an absent requested slot. */
fun validateNativeDeployment(action: NativeDecisionAction, deployed: Map<FieldSlot, TeamSlot>, reserves: List<TeamSlot>) {
    fun field(index: Int) {
        val slot = FieldSlot.list.getOrNull(index)
        require(slot != null && deployed[slot] != null && deployed[slot] != TeamSlot.Unknown) {
            "UnavailableNativeFieldSlot:$index"
        }
    }
    fun target(target: Int?) { if (target != null) field(target - 1) }
    when (action) {
        is NativeDecisionAction.ServantSkill -> { field(action.slot); target(action.target) }
        is NativeDecisionAction.MasterSkill -> target(action.target)
        is NativeDecisionAction.OrderChange -> {
            field(action.front)
            require(reserves.getOrNull(action.reserve)?.let { it != TeamSlot.Unknown } == true) {
                "UnavailableNativeReserveSlot:${action.reserve}"
            }
        }
        else -> Unit
    }
}
