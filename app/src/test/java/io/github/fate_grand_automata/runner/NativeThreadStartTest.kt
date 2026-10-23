package io.github.fate_grand_automata.runner
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.*

class NativeThreadStartTest {
    @Test fun `factory failure does not mark an executor started`() {
        var started=false
        assertFailsWith<IllegalStateException>{ startNativeEntryPoint({error("factory failed")}) {started=true} }
        assertFalse(started)
    }
    @Test fun `successfully started thread owns control before its exit callback`() {
        val end=CountDownLatch(1);var native:Thread?=null;var started=false
        try {
            startNativeEntryPoint({native=thread {end.await()}}) {started=true}
            assertTrue(started);assertTrue(requireNotNull(native).isAlive)
        } finally {end.countDown();native?.join(1000)}
    }
    @Test fun `screenshot close and diagnostic failure remain optional after input exit`() {
        var closed=0;var warned=0
        closeOptionalNativeResource({closed++;error("screenshot close failed")}) {warned++;error("logger failed")}
        assertEquals(1,closed);assertEquals(1,warned)
        val gate=ScriptRunGate();val reservation=requireNotNull(gate.reserve(ScriptRunGate.Owner.Automation))
        assertTrue(gate.finish(reservation));assertFalse(gate.isAutomationBusy())
        assertFalse(gate.finish(reservation))
    }
}
