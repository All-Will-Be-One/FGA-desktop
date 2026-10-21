package io.github.fate_grand_automata.scripts.decision

import io.github.lib_automata.Pattern
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDecisionTurnRunnerTest {
    private val skills = NativeDecisionContext(1, 1, 1, 1, NativeDecisionPhase.Skills)

    private fun hand(affinity: String = "Normal") = (1..5).map {
        NativeDecisionCard(it, "Arts", affinity, ((it % 3) + 1).toString(), it % 3, false)
    }

    private inner class Fixture(actions: List<NativeDecisionAction>) {
        val events = mutableListOf<String>()
        val contexts = mutableListOf<NativeDecisionContext>()
        val commands = ArrayDeque(actions.mapIndexed { index, action -> NativeDecisionCommand("plan$index", action) })
        var targeted = false
        var expired = false
        var cancelled = false
        var captures = 0
        var failCompletion = false
        var capturedOrder: List<Int>? = null
        val executedActions = mutableListOf<NativeDecisionAction>()
        val provider = object : NativeBattleDecisionProvider {
            override val enabled = true
            override fun await(context: NativeDecisionContext, capture: () -> Pattern, checkCancelled: () -> Unit): NativeDecisionCommand {
                checkCancelled()
                capture().close()
                contexts += context
                events += "ready:${context.phase}"
                return commands.removeFirst()
            }
            override fun executing(command: NativeDecisionCommand) {
                if (expired) error("ExpiredObservation")
                events += "executing:${command.planId}"
            }
            override fun completed(command: NativeDecisionCommand) {
                if (failCompletion) error("ReceiptPersistenceFailed")
                events += "completed:${command.planId}"
            }
            override fun failed(command: NativeDecisionCommand, error: String) { events += "unconfirmed:${command.planId}" }
            override fun beforeAttack(context: NativeDecisionContext, capture: () -> Pattern) {
                assertEquals(NativeDecisionPhase.Skills, context.phase)
                capture().close()
                events += "beforeAttack:$targeted"
            }
            override fun finishedTurn(context: NativeDecisionContext) { events += "awaitingNativeTransition" }
        }
        val native = object : NativeDecisionTurnExecutor {
            override fun checkCancelled() { check(!cancelled) { "Cancelled" } }
            override fun capture(): Pattern { captures++; return mockk(relaxed = true) }
            override fun verifyScene(context: NativeDecisionContext) { events += "verify:${context.phase}" }
            override fun executeSkill(action: NativeDecisionAction) {
                executedActions += action
                events += "effect:${action.javaClass.simpleName}"
                if (action is NativeDecisionAction.EnemyTarget) targeted = true
            }
            override fun enterCards(): List<NativeDecisionCard> {
                events += "attack"
                return hand(if (targeted) "Weak" else "Normal")
            }
            override fun returnToSkills() { events += "backToSkills" }
            override fun executeCards(order: List<Int>, cards: List<NativeDecisionCard>) {
                events += "effect:Cards"
                capturedOrder = order
            }
        }
        val runner = NativeDecisionTurnRunner(provider)
    }

    @Test
    fun `one skill produces a fresh observation before a separate attack and card plan`() {
        val f = Fixture(listOf(NativeDecisionAction.ServantSkill(0, 1), NativeDecisionAction.FinishSkills,
            NativeDecisionAction.Cards(listOf(6, 3, 2, 1))))
        f.runner.run(skills, f.native)
        assertEquals(listOf(NativeDecisionPhase.Skills, NativeDecisionPhase.Skills, NativeDecisionPhase.Cards), f.contexts.map { it.phase })
        assertEquals(4, f.captures) // Three fresh readies and the immediately pre-Attack parent.
        assertTrue(f.events.indexOf("executing:plan0") < f.events.indexOf("effect:ServantSkill"))
        assertTrue(f.events.indexOf("completed:plan0") < f.events.lastIndexOf("ready:Skills"))
        assertEquals(listOf(6, 3, 2, 1), f.capturedOrder)
        assertEquals("awaitingNativeTransition", f.events.last())
    }

    @Test
    fun `card enemy target backs out then refreshes both parent and hand before another plan`() {
        val f = Fixture(listOf(NativeDecisionAction.FinishSkills, NativeDecisionAction.EnemyTarget(1, 3),
            NativeDecisionAction.Cards(listOf(2, 4, 5))))
        f.runner.run(skills, f.native)
        assertEquals(listOf("Normal", "Weak"), f.contexts.filter { it.phase == NativeDecisionPhase.Cards }.map { it.cards.first().affinity })
        assertTrue(f.events.indexOf("backToSkills") < f.events.indexOf("effect:EnemyTarget"))
        assertEquals(listOf("beforeAttack:false", "beforeAttack:true"), f.events.filter { it.startsWith("beforeAttack:") })
        assertEquals(1, f.events.count { it == "effect:Cards" })
    }

    @Test
    fun `explicit native targets and no target pass unchanged to the native transaction`() {
        val actions = listOf(NativeDecisionAction.ServantSkill(0, 0, 1), NativeDecisionAction.MasterSkill(0, 2),
            NativeDecisionAction.ServantSkill(2, 1), NativeDecisionAction.OrderChange(0, 1))
        val f = Fixture(actions + listOf(NativeDecisionAction.FinishSkills, NativeDecisionAction.Cards(listOf(1, 2, 3))))
        f.runner.run(skills, f.native)
        assertEquals(actions, f.executedActions)
        assertEquals(4, f.events.count { it.startsWith("effect:") && it != "effect:Cards" })
    }

    @Test
    fun `unknown and six enemy layouts reject before inputs`() {
        for (count in listOf(null, 0, 6)) {
            val f = Fixture(listOf(NativeDecisionAction.EnemyTarget(0, count)))
            assertFailsWith<IllegalArgumentException> { f.runner.run(skills, f.native) }
            assertFalse(f.events.any { it.startsWith("effect:") })
        }
    }

    @Test
    fun `invalid native target and order change ranges stop before effects`() {
        for (action in listOf(NativeDecisionAction.ServantSkill(0, 0, 0), NativeDecisionAction.MasterSkill(0, 4),
            NativeDecisionAction.OrderChange(0, 3))) {
            val f = Fixture(listOf(action))
            assertFailsWith<IllegalArgumentException> { f.runner.run(skills, f.native) }
            assertFalse(f.events.any { it.startsWith("effect:") || it.startsWith("executing:") })
        }
    }

    @Test
    fun `expired execution checkpoint and cancellation never produce an effect`() {
        val expired = Fixture(listOf(NativeDecisionAction.ServantSkill(0, 0)))
        expired.expired = true
        assertFailsWith<IllegalStateException> { expired.runner.run(skills, expired.native) }
        assertFalse(expired.events.any { it.startsWith("effect:") })
        val cancelled = Fixture(listOf(NativeDecisionAction.FinishSkills))
        cancelled.cancelled = true
        assertFailsWith<IllegalStateException> { cancelled.runner.run(skills, cancelled.native) }
        assertTrue(cancelled.events.isEmpty())
    }

    @Test
    fun `receipt failure after transaction is unconfirmed and never runs another action`() {
        val f = Fixture(listOf(NativeDecisionAction.ServantSkill(0, 0), NativeDecisionAction.FinishSkills))
        f.failCompletion = true
        assertFailsWith<IllegalStateException> { f.runner.run(skills, f.native) }
        assertEquals(1, f.events.count { it.startsWith("effect:") })
        assertEquals("unconfirmed:plan0", f.events.last())
        assertEquals(1, f.commands.size)
    }

    @Test
    fun `plan identifiers cannot replay across turns`() {
        val f = Fixture(listOf(NativeDecisionAction.FinishSkills, NativeDecisionAction.Cards(listOf(1, 2, 3))))
        f.runner.run(skills, f.native)
        f.commands.add(NativeDecisionCommand("plan0", NativeDecisionAction.ServantSkill(0, 0)))
        assertFailsWith<IllegalArgumentException> { f.runner.run(skills.copy(turn = 2), f.native) }
        assertEquals(1, f.events.count { it.startsWith("effect:") })
    }

    @Test
    fun `partial unknown identity and illegal card fallback fail closed`() {
        assertFailsWith<IllegalArgumentException> { validateNativeHand(hand().dropLast(1)) }
        assertFailsWith<IllegalArgumentException> { validateNativeHand(hand().map { it.copy(servant = "unknown") }) }
        val context = skills.copy(phase = NativeDecisionPhase.Cards, cards = hand().map { if (it.index == 1) it.copy(stunned = true) else it })
        assertFailsWith<IllegalArgumentException> { validateNativeDecision(context, NativeDecisionAction.Cards(listOf(6, 1, 2, 3))) }
        assertFailsWith<IllegalArgumentException> { validateNativeDecision(context, NativeDecisionAction.Cards(listOf(6, 7, 8))) }
        assertFailsWith<IllegalArgumentException> { validateNativeDecision(context, NativeDecisionAction.Cards(listOf(2, 3, 3, 4))) }
    }
}
