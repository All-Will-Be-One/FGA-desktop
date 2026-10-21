package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.EnemyTarget
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.OrderChangeMember
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.skills
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.lib_automata.ExitManager
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

/** The same Back input used by upstream Battle; no extra scene/effect assertion. */
internal fun IFgoAutomataApi.returnToExternalSkills() {
    locations.attack.backClick.click()
}

/** Adapts external plans to upstream transactions; it contains no battle policy. */
@ScriptScope
class ExternalPlanBattle @Inject constructor(
    api: IFgoAutomataApi,
    private val state: BattleState,
    private val caster: Caster,
    private val cardExecutor: ExternalCardExecutor,
    private val decisionProvider: NativeBattleDecisionProvider,
    private val exitManager: ExitManager
) : IFgoAutomataApi by api {
    val enabled get() = decisionProvider.enabled
    private val externalPlans = NativeDecisionTurnRunner(decisionProvider)
    private var externalBattle = -1
    private var externalTurn = 0

    fun executeTurn(clickAttack: () -> Unit) {
        if (externalBattle != state.runs) {
            externalBattle = state.runs
            externalTurn = 0
        }
        externalTurn++
        val context = NativeDecisionContext(state.runs + 1, externalTurn, state.stage + 1,
            state.turn + 1, NativeDecisionPhase.Skills)
        externalPlans.run(context, object : NativeDecisionTurnExecutor {
            override fun checkCancelled() = exitManager.checkExitRequested()
            override fun capture() = useColor { locations.scriptArea.getPattern() }
            override fun executeSkill(action: NativeDecisionAction) {
                when (action) {
                    is NativeDecisionAction.ServantSkill -> {
                        caster.castServantSkill(FieldSlot.list[action.slot].skills()[action.index], nativeServantTarget(action.target))
                    }
                    is NativeDecisionAction.MasterSkill -> {
                        caster.castMasterSkill(Skill.Master.list[action.index], nativeServantTarget(action.target))
                    }
                    is NativeDecisionAction.OrderChange -> {
                        caster.orderChange(AutoSkillAction.OrderChange(OrderChangeMember.Starting.list[action.front],
                            OrderChangeMember.Sub.list[action.reserve]))
                    }
                    is NativeDecisionAction.EnemyTarget -> {
                        caster.selectEnemyTarget(EnemyTarget.list[action.index])
                        state.chosenTarget = EnemyTarget.list[action.index]
                    }
                    else -> throw NativeDecisionException("UnsupportedNativeSkillAction")
                }
                // Original Caster owns confirmation, target handling and animation waits.
                // Its return is delivery completion, never proof of the game effect.
            }
            override fun enterCards(): List<NativeDecisionCard> {
                clickAttack()
                return cardExecutor.readHand()
            }
            override fun returnToSkills() {
                this@ExternalPlanBattle.returnToExternalSkills()
            }
            override fun executeCards(order: List<Int>, cards: List<NativeDecisionCard>) {
                cardExecutor.executeOrder(order)
            }
        })
    }
}
