package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.enums.CardTypeEnum
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.modules.Card
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.lib_automata.ScriptAbortException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCardDeliveryTest {
    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val tracker = mockk<ServantTracker>()
        val caster = mockk<Caster>(relaxed = true)
        val card = mockk<Card>()
        val hand = CommandCard.Face.list.map {
            ParsedCard(it, TeamSlot.A, FieldSlot.A, CardTypeEnum.Arts)
        }
        val expected = hand.map { NativeDecisionCard(it.card.index, "Arts", "Normal", "1", 0, false) }
        val executor = ExternalCardExecutor(api, tracker, caster, card)
        var handReads = 0
        init {
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B)
            every { card.readCommandCards() } answers {
                handReads++
                check(handReads <= 3) { "Animation must be handled by native AutoBattle, not another hand/scene probe" }
                hand
            }
        }
    }

    @Test
    fun `explicit NP and face delivery returns to native loop without requiring a sampled animation cue`() {
        val f = Fixture()
        // The observed live order: two NPs and a face, followed by policy candidates.
        f.executor.executeOrder(listOf(7, 6, 2, 1, 3, 4, 5), f.expected)
        verifyOrder {
            f.caster.use(CommandCard.NP.list[1], closeWarning = true)
            f.caster.use(CommandCard.NP.list[0], closeWarning = true)
            f.caster.use(CommandCard.Face.list[1])
        }
        verify(exactly = 2) { f.caster.use(any<CommandCard.NP>(), any()) }
        verify(exactly = 1) { f.caster.use(any<CommandCard.Face>()) }
        verify(exactly = 0) { f.card.clickCommandCards(any(), any()) }
        assertEquals(3, f.handReads)
    }

    @Test
    fun `native gesture cancellation propagates without dispatching another card`() {
        val f = Fixture()
        every { f.caster.use(CommandCard.Face.list[0]) } throws ScriptAbortException()
        assertFailsWith<ScriptAbortException> {
            f.executor.executeOrder(listOf(1, 2, 3, 4, 5), f.expected)
        }
        verify(exactly = 1) { f.caster.use(any<CommandCard.Face>()) }
        assertEquals(1, f.handReads)
    }
}
