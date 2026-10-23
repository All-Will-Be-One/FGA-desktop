package io.github.fate_grand_automata.scripts.decision

import io.github.lib_automata.Pattern
import io.mockk.mockk
import kotlin.test.*

class NativeDecisionTurnRunnerTest {
    private val context = NativeDecisionContext(1, 1, 1, 1, NativeDecisionPhase.Skills)
    private inner class Fixture(sequence: List<NativeDecisionAction>) {
        val events = mutableListOf<String>()
        val phases = mutableListOf<NativeDecisionPhase>()
        val actions = mutableListOf<NativeDecisionAction>()
        val commands = ArrayDeque(sequence.mapIndexed { n, action -> NativeDecisionCommand("plan$n", action) })
        var captures = 0
        var cancelled = false
        var failReceipt = false
        var cancelAfterSkill = false
        var order: List<Int>? = null
        val provider = object : NativeBattleDecisionProvider {
            override val enabled = true
            override fun await(context: NativeDecisionContext, capture: () -> Pattern, checkCancelled: () -> Unit): NativeDecisionCommand {
                checkCancelled(); capture().close(); phases += context.phase
                events += "ready:${context.phase}"
                return commands.removeFirst()
            }
            override fun executing(command: NativeDecisionCommand) { events += "executing:${command.planId}" }
            override fun completed(command: NativeDecisionCommand) {
                check(!failReceipt) { "receipt persistence failed" }
                events += "completed:${command.planId}"
            }
            override fun failed(command: NativeDecisionCommand, error: String) { events += "failed:${command.planId}" }
            override fun finishedTurn(context: NativeDecisionContext) { events += "native-animation" }
        }
        val native = object : NativeDecisionTurnExecutor {
            override fun checkCancelled() { check(!cancelled) { "cancelled" } }
            override fun capture(): Pattern { captures++; return mockk(relaxed = true) }
            override fun executeSkill(action: NativeDecisionAction) {
                actions += action; events += "skill"
                if (cancelAfterSkill) cancelled = true
            }
            override fun delay(milliseconds: Long) { checkCancelled(); events += "delay:$milliseconds" }
            override fun enterCards(): List<NativeDecisionCard> { events += "attack"; return emptyList() }
            override fun executeCardTarget(index: Int) { events += "target:$index" }
            override fun executeCards(order: List<Int>, cards: List<NativeDecisionCard>) { this@Fixture.order = order; events += "cards" }
        }
        val runner = NativeDecisionTurnRunner(provider)
    }
    @Test fun `empty or nine skills need exactly two captures and phase requests`() {
        for (count in listOf(0, 9)) {
            val skills: List<NativeDecisionAction> = (0 until count).map { NativeDecisionAction.ServantSkill(it / 3, it % 3, it / 3 + 1) }
            val f = Fixture(listOf(NativeDecisionAction.Skills(skills), NativeDecisionAction.Cards(listOf(5, 3, 4), 1)))
            f.runner.run(context, f.native)
            assertEquals(2, f.captures)
            assertEquals(listOf(NativeDecisionPhase.Skills, NativeDecisionPhase.Cards), f.phases)
            assertEquals(skills, f.actions)
            assertEquals(count, f.events.count { it == "delay:1500" })
            assertEquals(1, f.events.count { it == "attack" })
            assertEquals(1, f.events.count { it == "target:1" })
            assertEquals(listOf(5, 3, 4), f.order)
            assertEquals(2, f.events.count { it.startsWith("executing:") })
            assertEquals("native-animation", f.events.last())
        }
    }
    @Test fun `explicit target and null pass unchanged to native Caster`() {
        val skills: List<NativeDecisionAction> = listOf(NativeDecisionAction.ServantSkill(0, 0, 1),
            NativeDecisionAction.MasterSkill(0, 2), NativeDecisionAction.MasterSkill(1), NativeDecisionAction.OrderChange(2, 1))
        val f = Fixture(listOf(NativeDecisionAction.Skills(skills, 2000), NativeDecisionAction.Cards(listOf(6, 7, 8))))
        f.runner.run(context, f.native)
        assertEquals(skills, f.actions)
        assertEquals(4, f.events.count { it == "delay:2000" })
        assertTrue(f.events.indexOf("executing:plan0") < f.events.indexOf("skill"))
        assertTrue(f.events.indexOf("completed:plan0") < f.events.indexOf("ready:Cards"))
    }
    @Test fun `bad nested action is rejected before any input`() {
        for (bad in listOf(NativeDecisionAction.ServantSkill(0, 0, 0), NativeDecisionAction.MasterSkill(0, 4),
            NativeDecisionAction.OrderChange(0, 3), NativeDecisionAction.EnemyTarget(0))) {
            val f = Fixture(listOf(NativeDecisionAction.Skills(listOf(NativeDecisionAction.ServantSkill(0, 0), bad))))
            assertFailsWith<IllegalArgumentException> { f.runner.run(context, f.native) }
            assertTrue(f.actions.isEmpty())
        }
    }
    @Test fun `standalone skill cannot create a third phase observation`() {
        val f = Fixture(listOf(NativeDecisionAction.ServantSkill(0, 0)))
        assertFailsWith<IllegalArgumentException> { f.runner.run(context, f.native) }
        assertTrue(f.actions.isEmpty())
    }
    @Test fun `explicit card order needs no quality or game effect proof`() {
        val f = Fixture(listOf(NativeDecisionAction.Skills(emptyList()), NativeDecisionAction.Cards(listOf(6, 7, 8))))
        f.runner.run(context, f.native) // No native card facts at all, explicit plan is still execution-only.
        assertEquals(listOf(6, 7, 8), f.order)
    }
    @Test fun `cancellation during native wait prevents later skill and never replays`() {
        val f = Fixture(listOf(NativeDecisionAction.Skills(listOf(NativeDecisionAction.ServantSkill(0, 0), NativeDecisionAction.ServantSkill(0, 1)))))
        f.cancelAfterSkill = true
        assertFailsWith<IllegalStateException> { f.runner.run(context, f.native) }
        assertEquals(listOf<NativeDecisionAction>(NativeDecisionAction.ServantSkill(0, 0)), f.actions)
        assertEquals("failed:plan0", f.events.last())
        assertFalse(f.events.contains("attack"))
        assertEquals(1, f.captures)
    }
    @Test fun `receipt failure stops this executor without repeating dispatched batch`() {
        val f = Fixture(listOf(NativeDecisionAction.Skills(listOf(NativeDecisionAction.ServantSkill(0, 0)))))
        f.failReceipt = true
        assertFailsWith<IllegalStateException> { f.runner.run(context, f.native) }
        assertEquals(1, f.actions.size)
        assertEquals("failed:plan0", f.events.last())
        assertEquals(1, f.captures)
    }
    @Test fun `request identity cannot replay across turns`() {
        val f = Fixture(listOf(NativeDecisionAction.Skills(emptyList()), NativeDecisionAction.Cards(listOf(1, 2, 3))))
        f.runner.run(context, f.native)
        f.commands.add(NativeDecisionCommand("plan0", NativeDecisionAction.Skills(listOf(NativeDecisionAction.ServantSkill(0, 0)))))
        assertFailsWith<IllegalArgumentException> { f.runner.run(context.copy(turn = 2), f.native) }
        assertTrue(f.actions.isEmpty())
    }
    @Test fun `card slots and optional enemy index retain basic parameter bounds`() {
        val c = context.copy(phase = NativeDecisionPhase.Cards)
        for (order in listOf(listOf(1, 1, 2), listOf(0, 2, 3), listOf(1, 2, 9), listOf(1, 2), listOf(1, 2, 3, 4, 5, 6, 7, 8, 9))) {
            assertFailsWith<IllegalArgumentException> { validateNativeDecision(c, NativeDecisionAction.Cards(order)) }
        }
        assertFailsWith<IllegalArgumentException> { validateNativeDecision(c, NativeDecisionAction.Cards(listOf(1,2,3), 3)) }
    }
}
