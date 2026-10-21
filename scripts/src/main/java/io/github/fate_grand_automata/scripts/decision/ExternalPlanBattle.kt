package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.EnemyTarget
import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.OrderChangeMember
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.fate_grand_automata.scripts.models.skills
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.lib_automata.ExitManager
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** A transition can still show Cards on the first post-gesture capture. */
internal fun IFgoAutomataApi.returnToExternalSkills() {
    locations.attack.backClick.click()
    // Reuse the upstream cancellable matcher loop and normal similarity. Do
    // not freeze a snapshot around this wait or repeat the Back input.
    if (!locations.battle.screenCheckRegion.exists(images[Images.BattleScreen], 5.seconds)) {
        throw NativeDecisionException("NativeReturnToSkillsTimeout")
    }
}

/** Adapts external plans to upstream transactions; it contains no battle policy. */
@ScriptScope
class ExternalPlanBattle @Inject constructor(
    api: IFgoAutomataApi,
    private val servantTracker: ServantTracker,
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

    fun verifyBeforeTurn() {
        if (enabled) requireStableExternalSkillsScene()
    }

    private fun externalSkillsSceneVisible(): Boolean = useSameSnapIn {
        images[Images.BattleScreen] in locations.battle.screenCheckRegion &&
            images[Images.SkillUse] !in locations.battle.skillUseRegion &&
            images[Images.Cancel] !in locations.battle.master.cancelCommandSpellRegion &&
            FieldSlot.list.any { locations.battle.servantPresentRegion(it).exists(images[Images.ServantExist], similarity = 0.70) }
    }

    private fun requireStableExternalSkillsScene() {
        if (!externalSkillsSceneVisible()) throw NativeDecisionException("UnsupportedNativeSkillSceneEvidence")
        0.5.seconds.wait()
        if (!externalSkillsSceneVisible()) throw NativeDecisionException("UnsupportedNativeSkillSceneEvidence")
    }

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
            override fun verifyScene(context: NativeDecisionContext) {
                when (context.phase) {
                    NativeDecisionPhase.Skills -> requireStableExternalSkillsScene()
                    NativeDecisionPhase.Cards -> cardExecutor.verifyHand(context.cards)
                }
            }
            override fun validateAction(action: NativeDecisionAction) =
                validateNativeDeployment(action, servantTracker.deployed, servantTracker.reserves)
            override fun executeSkill(action: NativeDecisionAction) {
                validateAction(action)
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
                // Caster preserves upstream bounded waits. Delivery does not prove a game effect.
                requireStableExternalSkillsScene()
                if (action !is NativeDecisionAction.EnemyTarget) {
                    servantTracker.beginTurn()
                    requireStableExternalSkillsScene()
                }
            }
            override fun enterCards(): List<NativeDecisionCard> {
                requireStableExternalSkillsScene()
                clickAttack()
                return cardExecutor.readHand()
            }
            override fun returnToSkills() {
                this@ExternalPlanBattle.returnToExternalSkills()
                requireStableExternalSkillsScene()
            }
            override fun executeCards(order: List<Int>, cards: List<NativeDecisionCard>) {
                cardExecutor.executeOrder(order, cards)
            }
        })
    }
}
