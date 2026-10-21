package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.IImageLoader
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.*
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.ExitManager
import io.github.lib_automata.Pattern
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

class BattleExecutionIsolationTest {
    private class Fixture(external: Boolean) {
        val prefs = mockk<IPreferences>(relaxed = true)
        val images = mockk<IImageLoader>()
        val locations = mockk<Locations>(relaxed = true)
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val config = mockk<IBattleConfig>(relaxed = true)
        val tracker = mockk<ServantTracker>(relaxed = true)
        val state = BattleState()
        val autoSkill = mockk<AutoSkill>()
        val caster = mockk<Caster>(relaxed = true)
        val card = mockk<Card>(relaxed = true)
        val externalCards = mockk<ExternalCardExecutor>(relaxed = true)
        val spam = mockk<SkillSpam>(relaxed = true)
        val stage = mockk<StageTracker>()
        val autoTarget = mockk<AutoChooseTarget>(relaxed = true)
        val provider = mockk<NativeBattleDecisionProvider>(relaxed = true)
        var sceneVisible = true
        var confirmationVisible = false
        var cancelVisible = false
        val battle: Battle
        init {
            every { api.prefs } returns prefs
            every { api.images } returns images
            every { api.locations } returns locations
            every { api.useSameSnapIn(any<() -> Any?>()) } answers { firstArg<() -> Any?>().invoke() }
            every { prefs.waitBeforeTurn } returns Duration.ZERO
            every { prefs.waitBeforeCards } returns Duration.ZERO
            val attack = mockk<Pattern>()
            val confirm = mockk<Pattern>()
            val cancel = mockk<Pattern>()
            val present = mockk<Pattern>()
            every { images[Images.BattleScreen, any()] } returns attack
            every { images[Images.SkillUse, any()] } returns confirm
            every { images[Images.Cancel, any()] } returns cancel
            every { images[Images.ServantExist, any()] } returns present
            // MockK does not invoke the default contains -> exists body on a relaxed interface.
            every { with(api) { any<Region>().contains(attack) } } answers { sceneVisible }
            every { with(api) { any<Region>().contains(confirm) } } answers { confirmationVisible }
            every { with(api) { any<Region>().contains(cancel) } } answers { cancelVisible }
            every { with(api) { any<Region>().exists(attack, any(), any()) } } answers { sceneVisible }
            every { with(api) { any<Region>().exists(confirm, any(), any()) } } answers { confirmationVisible }
            every { with(api) { any<Region>().exists(cancel, any(), any()) } } answers { cancelVisible }
            every { with(api) { any<Region>().exists(present, any(), any()) } } returns true
            every { stage.checkCurrentStage() } answers { state.nextStage(); Unit }
            every { autoSkill.execute(any(), any()) } returns NPUsage.none
            every { provider.enabled } returns external
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.C)
            val hand = (1..5).map { NativeDecisionCard(it, "Arts", "Normal", "1", 0, false) }
            every { externalCards.handFacts(any()) } returns hand
            every { provider.await(any(), any(), any()) } returnsMany listOf(
                NativeDecisionCommand("skills", NativeDecisionAction.Skills(listOf(NativeDecisionAction.ServantSkill(1, 2)))),
                NativeDecisionCommand("cards", NativeDecisionAction.Cards(listOf(2, 4, 1)))
            )
            val externalPlans = ExternalPlanBattle(api, state, caster, externalCards, provider, ExitManager())
            battle = Battle(api, tracker, state, config, autoSkill, caster, card, spam,
                mockk(relaxed = true), stage, autoTarget, externalPlans)
        }
    }

    @Test
    fun `external mode executes only the supplied semantic skill and card order`() {
        val f = Fixture(external = true)
        f.battle.performBattle()
        verify(exactly = 1) { f.caster.castServantSkill(Skill.Servant.B3, null) }
        verify(exactly = 1) { f.externalCards.executeOrder(listOf(2, 4, 1)) }
        verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
        verify(exactly = 0) { f.spam.spamSkills() }
        verify(exactly = 0) { f.autoTarget.choose() }
        verify(exactly = 0) { f.card.clickCommandCards(any(), any()) }
        verify(exactly = 1) { f.provider.finishedTurn(any()) }
    }

    @Test
    fun `default script mode keeps the original script spam and card execution path`() {
        val f = Fixture(external = false)
        f.battle.performBattle()
        verify(exactly = 1) { f.autoSkill.execute(any(), any()) }
        verify(exactly = 1) { f.spam.spamSkills() }
        verify(exactly = 1) { f.card.clickCommandCards(any(), NPUsage.none) }
        verify(exactly = 0) { f.externalCards.executeOrder(any()) }
        verify(exactly = 0) { f.provider.await(any(), any(), any()) }
    }

    @Test
    fun `card delivery can finish during animation without publishing another actionable turn`() {
        val f = Fixture(external = true)
        every { f.externalCards.executeOrder(any()) } answers { f.sceneVisible = false }
        f.battle.performBattle()
        assertTrue(!f.battle.isIdle())
        verify(exactly = 1) { f.provider.completed(match { it.planId == "cards" }) }
        verify(exactly = 1) { f.provider.finishedTurn(any()) }
        verify(exactly = 2) { f.provider.await(any(), any(), any()) }
        // AutoBattle's existing screen loop cannot enter performBattle again
        // until its normal native BattleScreen detector sees an idle scene.
        f.sceneVisible = true
        assertTrue(f.battle.isIdle())
    }

    @Test
    fun `explicit external target and order change reach the original Caster unchanged`() {
        val f = Fixture(external = true)
        every { f.provider.await(any(), any(), any()) } returnsMany listOf(
                NativeDecisionCommand("skills", NativeDecisionAction.Skills(listOf(NativeDecisionAction.ServantSkill(1, 2, 3), NativeDecisionAction.MasterSkill(0, 2), NativeDecisionAction.MasterSkill(1), NativeDecisionAction.OrderChange(2, 1)))),
                NativeDecisionCommand("cards", NativeDecisionAction.Cards(listOf(1, 2, 3)))
            )
        f.battle.performBattle()
        verify(exactly = 1) { f.caster.castServantSkill(Skill.Servant.B3, ServantTarget.C) }
        verify(exactly = 1) { f.caster.castMasterSkill(Skill.Master.A, ServantTarget.B) }
        verify(exactly = 1) { f.caster.castMasterSkill(Skill.Master.B, null) }
        verify(exactly = 1) { f.caster.orderChange(match {
            it.starting == OrderChangeMember.Starting.C && it.sub == OrderChangeMember.Sub.B
        }) }
        verify(exactly = 0) { f.autoSkill.execute(any(), any()) }
        verify(exactly = 0) { f.spam.spamSkills() }
        verify(exactly = 0) { f.autoTarget.choose() }
    }

    @Test
    fun `unrecognized deployment does not replace explicit target or prevent native execution`() {
        val f = Fixture(external = true)
        every { f.tracker.deployed } returns emptyMap()
        every { f.provider.await(any(), any(), any()) } returnsMany listOf(
                NativeDecisionCommand("skills", NativeDecisionAction.Skills(listOf(NativeDecisionAction.ServantSkill(1, 2, 1)))),
                NativeDecisionCommand("cards", NativeDecisionAction.Cards(listOf(6, 7, 8)))
            )
        f.battle.performBattle()
        verify(exactly = 1) { f.caster.castServantSkill(Skill.Servant.B3, ServantTarget.A) }
        verify(exactly = 1) { f.externalCards.executeOrder(listOf(6, 7, 8)) }
    }

    @Test
    fun `no additional scene or effect proof interrupts supplied native sequence`() {
        for (scene in listOf("missing", "confirmation", "cancel")) {
            val f = Fixture(external = true)
            every { f.caster.castServantSkill(Skill.Servant.B3, null) } answers {
                when (scene) {
                    "missing" -> f.sceneVisible = false
                    "confirmation" -> f.confirmationVisible = true
                    "cancel" -> f.cancelVisible = true
                }
            }
            f.battle.performBattle()
            verify(exactly = 1) { f.caster.castServantSkill(Skill.Servant.B3, null) }
            verify(exactly = 1) { f.externalCards.executeOrder(listOf(2, 4, 1)) }
            verify(exactly = 0) { f.provider.failed(any(), any()) }
            verify(exactly = 2) { f.provider.completed(any()) }
        }
    }
    @Test
    fun `card target uses only upstream header without dismissal or repeated attack`() {
        val f = Fixture(external = true)
        every { f.provider.await(any(), any(), any()) } returnsMany listOf(
            NativeDecisionCommand("skills", NativeDecisionAction.Skills(emptyList())),
            NativeDecisionCommand("cards", NativeDecisionAction.Cards(listOf(5, 3, 4), 1))
        )
        f.battle.performBattle()
        verify(exactly = 0) { f.caster.selectEnemyTarget(any()) }
        verify(exactly = 1) { f.externalCards.executeOrder(listOf(5, 3, 4)) }
        verify(exactly = 1) { f.externalCards.handFacts(any()) }
        verify(exactly = 2) { f.provider.await(any(), any(), any()) }
    }

}
