package io.github.fate_grand_automata.scripts.decision

import io.github.lib_automata.Pattern

enum class NativeDecisionPhase { Skills, Cards }

data class NativeDecisionCard(
    val index: Int,
    val type: String,
    val affinity: String,
    val servant: String,
    val fieldSlot: Int?,
    val stunned: Boolean
)

data class NativeDecisionContext(
    val battle: Int,
    val turn: Int,
    val wave: Int,
    val waveTurn: Int,
    val phase: NativeDecisionPhase,
    val cards: List<NativeDecisionCard> = emptyList()
)

sealed class NativeDecisionAction {
    // target is an explicit native ally-dialog click, never a policy recipient hint.
    // FGOAgent supplies the target intent; native Caster keeps its original direct-target semantics.
    data class ServantSkill(val slot: Int, val index: Int, val target: Int? = null) : NativeDecisionAction()
    data class MasterSkill(val index: Int, val target: Int? = null) : NativeDecisionAction()
    data class OrderChange(val front: Int, val reserve: Int) : NativeDecisionAction()
    data class EnemyTarget(val index: Int, val enemyCount: Int? = null) : NativeDecisionAction()
    /** Explicit external request for another frame, with no game input. */
    data object Observe : NativeDecisionAction()
    data object FinishSkills : NativeDecisionAction()
    data class Cards(val order: List<Int>) : NativeDecisionAction()
}

data class NativeDecisionCommand(val planId: String, val action: NativeDecisionAction)

interface NativeBattleDecisionProvider {
    val enabled: Boolean
    fun await(context: NativeDecisionContext, capture: () -> Pattern, checkCancelled: () -> Unit): NativeDecisionCommand
    fun executing(command: NativeDecisionCommand)
    fun completed(command: NativeDecisionCommand)
    fun failed(command: NativeDecisionCommand, error: String)
    /** Fresh post-skill parent for the immediately following Attack transition only. */
    fun beforeAttack(context: NativeDecisionContext, capture: () -> Pattern) {}
    /** Dispatch completed; a new turn or result still has to be observed by native AutoBattle. */
    fun finishedTurn(context: NativeDecisionContext)
}

object DisabledNativeBattleDecisionProvider : NativeBattleDecisionProvider {
    override val enabled = false
    override fun await(context: NativeDecisionContext, capture: () -> Pattern, checkCancelled: () -> Unit): NativeDecisionCommand =
        error("External battle decisions are disabled")
    override fun executing(command: NativeDecisionCommand) {}
    override fun completed(command: NativeDecisionCommand) {}
    override fun failed(command: NativeDecisionCommand, error: String) {}
    override fun finishedTurn(context: NativeDecisionContext) {}
}
