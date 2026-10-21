package io.github.fate_grand_automata.runner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptRunGateTest {
    @Test
    fun `interactive and API starts exclude one another before preparation`() {
        for (owner in ScriptRunGate.Owner.entries) {
            val gate = ScriptRunGate()
            val reservation = assertNotNull(gate.reserve(owner))
            assertNull(gate.reserve(ScriptRunGate.Owner.Interactive))
            assertNull(gate.reserve(ScriptRunGate.Owner.Automation))
            assertEquals(owner == ScriptRunGate.Owner.Automation, gate.isAutomationBusy())
            assertTrue(gate.finish(reservation))
            assertNotNull(gate.reserve(ScriptRunGate.Owner.Automation))
        }
    }

    @Test
    fun `cancel during preparation never invokes the native start callback`() {
        val gate = ScriptRunGate()
        val reservation = assertNotNull(gate.reserve(ScriptRunGate.Owner.Automation))
        assertTrue(gate.requestStop(automationOnly = true) === reservation)
        var inputsStarted = false
        assertFalse(gate.start(reservation) { inputsStarted = true })
        assertFalse(inputsStarted)
        assertFalse(gate.canPrepare(reservation))
        assertTrue(gate.isAutomationBusy())
        assertNull(gate.reserve(ScriptRunGate.Owner.Interactive))
        assertTrue(gate.finish(reservation))
        assertFalse(gate.finish(reservation))
    }

    @Test
    fun `API stop cannot cancel a manual run and stale callbacks cannot release a new run`() {
        val gate = ScriptRunGate()
        val manual = assertNotNull(gate.reserve(ScriptRunGate.Owner.Interactive))
        assertNull(gate.requestStop(automationOnly = true))
        assertTrue(gate.canPrepare(manual))
        assertTrue(gate.finish(manual))
        val api = assertNotNull(gate.reserve(ScriptRunGate.Owner.Automation))
        assertFalse(gate.finish(manual))
        var staleStopReachedNewRun = false
        assertFalse(gate.withReservation(manual) { staleStopReachedNewRun = true })
        assertFalse(staleStopReachedNewRun)
        assertTrue(gate.canPrepare(api))
    }

    @Test
    fun `concurrent callers get exactly one reservation`() {
        val gate = ScriptRunGate()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val ready = CountDownLatch(8)
            val start = CountDownLatch(1)
            val futures = (1..8).map {
                pool.submit<Boolean> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    gate.reserve(ScriptRunGate.Owner.Automation) != null
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertEquals(1, futures.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `recreated service shares process ownership until the old executor is released`() {
        val originalServiceGate = ProcessScriptRunGate.gate
        val recreatedServiceGate = ProcessScriptRunGate.gate
        val oldRun = assertNotNull(originalServiceGate.reserve(ScriptRunGate.Owner.Automation))
        try {
            assertTrue(originalServiceGate.requestStop(expected = oldRun) === oldRun)
            assertNull(recreatedServiceGate.reserve(ScriptRunGate.Owner.Interactive))
            assertNull(recreatedServiceGate.reserve(ScriptRunGate.Owner.Automation))
            assertTrue(originalServiceGate.finish(oldRun))
            val next = assertNotNull(recreatedServiceGate.reserve(ScriptRunGate.Owner.Interactive))
            assertFalse(originalServiceGate.finish(oldRun))
            assertNull(originalServiceGate.requestStop(expected = oldRun))
            assertTrue(recreatedServiceGate.finish(next))
        } finally {
            originalServiceGate.finish(oldRun)
        }
    }

    @Test
    fun `idle service buffer cleanup waits for the process executor before another start`() {
        val gate = ScriptRunGate()
        var cleanups = 0
        gate.afterCurrentRun { cleanups++ }
        assertEquals(1, cleanups)
        val old = assertNotNull(gate.reserve(ScriptRunGate.Owner.Automation))
        gate.afterCurrentRun { cleanups++ }
        assertEquals(1, cleanups)
        assertNull(gate.reserve(ScriptRunGate.Owner.Interactive))
        assertTrue(gate.finish(old))
        assertEquals(2, cleanups)
        val next = assertNotNull(gate.reserve(ScriptRunGate.Owner.Interactive))
        assertTrue(gate.finish(next))
        assertEquals(2, cleanups)
    }
}
