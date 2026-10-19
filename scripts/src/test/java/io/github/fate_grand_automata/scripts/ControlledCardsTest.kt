package io.github.fate_grand_automata.scripts

import io.github.fate_grand_automata.scripts.locations.AttackScreenLocations
import io.github.fate_grand_automata.scripts.models.CommandCard
import io.github.fate_grand_automata.scripts.modules.*
import io.github.lib_automata.AutomataApi
import io.github.lib_automata.Location
import io.mockk.every
import io.mockk.mockk
import kotlin.test.*

class ControlledCardsTest {
    private val all = CommandCard.Face.list + CommandCard.NP.list
    private val api = mockk<AutomataApi>()
    private val locations = mockk<AttackScreenLocations>()
    private val inputs = mutableListOf<Location>()
    private var now = 1000L
    private var failureAt = -1
    private var cancelAt = -1
    private val runner = ControlledCards(api, locations, { now }, checkControl = {
        check(inputs.size != cancelAt) { "cancelled" }
    })
    init {
        all.forEachIndexed { index, card -> every { locations.clickLocation(card) } returns Location(index, 0) }
        every { with(api) { any<Location>().click(any()) } } answers {
            inputs.add(firstArg())
            if (inputs.size == failureAt) error("transport failure")
        }
    }
    private fun frame(id: String = "one", time: Long = 1000, partial: Boolean = false,
                      available: Map<CommandCard, Boolean?> = all.associateWith { true }) =
        CardInputEvidence(id, time, "fixture", true, partial, available)
    private fun frames(plan: List<CommandCard>) =
        if (plan.any { it is CommandCard.NP }) listOf(frame("one", 800), frame("two", 1000)) else listOf(frame())

    @Test fun exactOrderWithZeroThroughThreeNPs() {
        for (n in 0..3) {
            val localInputs = mutableListOf<Location>()
            val localApi = mockk<AutomataApi>()
            every { with(localApi) { any<Location>().click(any()) } } answers { localInputs.add(firstArg()); Unit }
            val local = ControlledCards(localApi, locations, { now }, {})
            val plan = CommandCard.NP.list.take(n) + CommandCard.Face.list.reversed().take(3-n)
            assertEquals(CardDispatch(3), local.dispatch(plan, frames(plan)))
            assertEquals(plan.map { locations.clickLocation(it) }, localInputs)
        }
    }
    @Test fun rejectsPartialDisabledUnknownAndStaleBeforeInput() {
        val plan = CommandCard.Face.list.take(3)
        for (f in listOf(frame(partial=true), frame(time=-30000),
            frame(available=all.associateWith { if(it == plan[0]) false else true }),
            frame(available=emptyMap()))) {
            assertFailsWith<IllegalArgumentException> { runner.dispatch(plan, listOf(f)) }
        }
        assertTrue(inputs.isEmpty())
    }
    @Test fun requiresTwoSeparatedNPFrames() {
        val plan = listOf(CommandCard.NP.A, CommandCard.Face.A, CommandCard.Face.B)
        assertFailsWith<IllegalArgumentException> { runner.dispatch(plan, listOf(frame())) }
        assertFailsWith<IllegalArgumentException> { runner.dispatch(plan, listOf(frame("one", 900),frame("two"))) }
        assertTrue(inputs.isEmpty())
    }
    @Test fun latestFusedNPEvidenceAllowsBlinkButNeverLatestSeal() {
        val plan = listOf(CommandCard.NP.A, CommandCard.Face.A, CommandCard.Face.B)
        val dark = frame("dark", 800, available=all.associateWith { it !is CommandCard.NP })
        val sealed = frame("sealed", 1000, available=all.associateWith { it !is CommandCard.NP })
        assertFailsWith<IllegalArgumentException> { runner.dispatch(plan, listOf(dark,sealed)) }
        assertTrue(inputs.isEmpty())
        assertEquals(3, runner.dispatch(plan, listOf(dark,frame("fused",1000))).attempted)
    }
    @Test fun expiryDuringControlCheckNeverDispatches() {
        val local = ControlledCards(api, locations, { now }, { now=32_000 })
        assertFailsWith<IllegalStateException> { local.dispatch(CommandCard.Face.list.take(3), listOf(frame())) }
        assertTrue(inputs.isEmpty())
    }
    @Test fun uncertainDeliveryStopsAndConsumesFrames() {
        val plan = CommandCard.Face.list.take(3)
        failureAt = 2
        assertEquals(2, assertFailsWith<CardDeliveryUnknown> { runner.dispatch(plan, frames(plan)) }.attempted)
        assertFailsWith<IllegalArgumentException> { runner.dispatch(plan, frames(plan)) }
        assertEquals(2, inputs.size)
    }
    @Test fun cancellationStopsBeforeNextInput() {
        val plan = CommandCard.Face.list.take(3)
        cancelAt = 1
        assertFailsWith<IllegalStateException> { runner.dispatch(plan, frames(plan)) }
        assertEquals(1, inputs.size)
    }
    @Test fun rejectsDuplicateOrShortPlans() {
        assertFailsWith<IllegalArgumentException> { runner.dispatch(listOf(CommandCard.Face.A), listOf(frame())) }
        assertFailsWith<IllegalArgumentException> { runner.dispatch(List(3) { CommandCard.Face.A }, listOf(frame())) }
        assertTrue(inputs.isEmpty())
    }
    @Test fun reviewPartialGuardFailureCarriesAttemptCount() {
        cancelAt=1
        val error=assertFails { runner.dispatch(CommandCard.Face.list.take(3),listOf(frame())) }
        val getter=error.javaClass.methods.find { it.name=="getAttempted" }
        assertNotNull(getter,"partial input evidence must survive control failure")
        assertEquals(1,getter.invoke(error))
    }

    @Test fun reviewAllGuardBoundariesKeepKnownAttempts() {
        for(n in 0..2) {
            inputs.clear();cancelAt=n
            val local=ControlledCards(api,locations,{now},{check(inputs.size!=cancelAt)})
            val error=assertFailsWith<CardDispatchInterrupted> {local.dispatch(CommandCard.Face.list.take(3),listOf(frame()))}
            assertEquals(n,error.attempted);assertFalse(error.latestAttemptUncertain)
        }
    }
    @Test fun reviewTransactionBudgetIncludesInterInputWait() {
        val local=ControlledCards(api,locations,{now},{},{now+=300})
        val error=assertFailsWith<CardDispatchInterrupted> {local.dispatch(CommandCard.Face.list.take(3),listOf(frame()),1100)}
        assertEquals(1,error.attempted);assertFalse(error.latestAttemptUncertain);assertEquals(1,inputs.size)
    }

}
