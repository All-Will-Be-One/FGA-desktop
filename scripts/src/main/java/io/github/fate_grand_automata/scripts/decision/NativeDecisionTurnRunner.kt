package io.github.fate_grand_automata.scripts.decision

import io.github.lib_automata.Pattern

/** Native implementation supplies only the existing Caster/Card transactions. */
interface NativeDecisionTurnExecutor {
    fun checkCancelled()
    fun capture(): Pattern
    fun executeSkill(action: NativeDecisionAction)
    fun enterCards(): List<NativeDecisionCard>
    fun delay(milliseconds: Long)
    fun executeCardTarget(index: Int)
    fun executeCards(order: List<Int>, cards: List<NativeDecisionCard>)
}

class NativeDecisionException(message: String) : IllegalStateException(message)

/** Executes externally supplied plans. It never chooses skills, targets, card order, or a fallback. */
class NativeDecisionTurnRunner(private val provider: NativeBattleDecisionProvider) {
    private val seenPlans = mutableSetOf<String>()

    fun run(initial: NativeDecisionContext, native: NativeDecisionTurnExecutor) {
        require(provider.enabled) { "Smart mode requires an enabled native decision provider" }
        var context = initial.copy(phase = NativeDecisionPhase.Skills, cards = emptyList())
        while (true) {
            native.checkCancelled()
            val command = provider.await(context, native::capture, native::checkCancelled)
            try {
                require(command.planId.isNotBlank() && command.planId.length <= 128) { "Invalid native plan identifier" }
                require(seenPlans.size < 10_000 && seenPlans.add(command.planId)) { "Native plan cannot be replayed" }
                require(command.action is NativeDecisionAction.Skills || command.action is NativeDecisionAction.Cards) { "A phase plan must be skills or cards" }
                validateNativeDecision(context, command.action)
                native.checkCancelled()
                // Persist dispatch against the exact observation before any input.
                provider.executing(command)
                native.checkCancelled()
                when (val action = command.action) {
                    is NativeDecisionAction.Skills -> {
                        action.actions.forEach { skill ->
                            native.checkCancelled()
                            native.executeSkill(skill)
                            native.delay(action.delayMillis)
                        }
                        context = context.copy(phase = NativeDecisionPhase.Cards, cards = native.enterCards())
                    }
                    is NativeDecisionAction.Cards -> {
                        action.target?.let { native.executeCardTarget(it) }
                        native.executeCards(action.order, context.cards)
                        provider.completed(command)
                        provider.finishedTurn(context)
                        return
                    }
                    else -> error("A phase plan must be a skill batch or an explicit card plan")
                }
                provider.completed(command)
                // One observation at each phase boundary, never between batched skills.
            } catch (error: Exception) {
                runCatching { provider.failed(command, error.message ?: error.javaClass.simpleName) }
                    .onFailure { error.addSuppressed(it) }
                throw error
            }
        }
    }
}

fun validateNativeDecision(context: NativeDecisionContext, action: NativeDecisionAction) {
    fun target(target: Int?) {
        require(target == null || target in 1..3) { "Unsupported servant target" }
    }
    when (action) {
        is NativeDecisionAction.ServantSkill -> {
            require(context.phase == NativeDecisionPhase.Skills && action.slot in 0..2 && action.index in 0..2)
            target(action.target)
        }
        is NativeDecisionAction.MasterSkill -> {
            require(context.phase == NativeDecisionPhase.Skills && action.index in 0..2)
            target(action.target)
        }
        is NativeDecisionAction.OrderChange -> {
            require(context.phase == NativeDecisionPhase.Skills && action.front in 0..2 && action.reserve in 0..2)
        }
        is NativeDecisionAction.EnemyTarget -> require(action.index in 0..2) { "Unsupported native enemy slot" }
        is NativeDecisionAction.Skills -> {
            require(context.phase == NativeDecisionPhase.Skills)
            require(action.delayMillis in 0..10_000) { "Invalid skill interval" }
            action.actions.forEach {
                require(it is NativeDecisionAction.ServantSkill || it is NativeDecisionAction.MasterSkill || it is NativeDecisionAction.OrderChange) { "Only explicit skill actions belong in a skill batch" }
                validateNativeDecision(context, it)
            }
        }
        NativeDecisionAction.FinishSkills -> require(context.phase == NativeDecisionPhase.Skills)
        is NativeDecisionAction.Cards -> {
            require(context.phase == NativeDecisionPhase.Cards)
            require(action.target == null || action.target in 0..2) { "Unsupported enemy target" }
            require(action.order.size in 3..8 && action.order.distinct().size == action.order.size && action.order.all { it in 1..8 }) {
                "Cards require three to eight explicit, distinct native slots"
            }
        }
    }
}
