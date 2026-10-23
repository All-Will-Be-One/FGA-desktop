package io.github.fga.automation

import kotlin.test.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.serialization.json.*

class AutomationControllerTest {
    @Test fun diagnosticDoesNotStartBattleOrChangeControllerState() {
        val backend = Backend()
        val c = controller(backend = backend)
        var captures = 0
        val rpc = RpcDispatcher(c, { buildJsonObject {} }, { buildJsonObject {} }, diagnostic = {
            captures++
            buildJsonObject { put("captureDiagnosticVersion", 1) }
        })
        val response = Json.parseToJsonElement(rpc.request("""{"protocolVersion":1,"op":"diagnostic.capture"}""")).jsonObject
        assertTrue(response.getValue("ok").jsonPrimitive.boolean)
        assertEquals(1, captures)
        assertTrue(backend.starts.isEmpty())
        assertTrue(backend.controls.isEmpty())
    }
    class Store:JournalStore {
        var value=Journal();var fail=false
        override fun load()=value
        override fun save(journal:Journal) { check(!fail);value=journal }
    }
    class Backend:NativeBattleBackend {
        val starts=mutableListOf<RunSnapshot>();val controls=mutableListOf<Pair<String,String>>()
        val callbacks=mutableMapOf<String,NativeBattleCallback>();var throwOnStart=false
        override fun start(run:RunSnapshot,callback:NativeBattleCallback) {
            starts+=run;callbacks[run.runId]=callback
            if(throwOnStart)error("reply lost after dispatch")
        }
        override fun control(runId:String,op:String) {controls+=runId to op}
    }
    private fun controller(store:Store=Store(),backend:Backend=Backend()):AutomationController {
        var id=0
        return AutomationController(store,backend,now={100L},newId={"run-${++id}"})
    }
    private fun fileStore(file:java.io.File)=FileJournalStore(file) { directory ->
        java.nio.channels.FileChannel.open(directory.toPath(),java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }
    private val limits=RunLimits(1,60)

    @Test fun ordinaryJournalFailureDoesNotBlockStopOrConfirmedExit() {
        val store=Store();val b=Backend();val c=controller(store,b);val a=c.start("r","config",limits)
        store.fail=true
        c.control(a.runId,"stop","stop")
        assertEquals(a.runId to "stop",b.controls.single())
        b.callbacks.getValue(a.runId).exit("Abort",0)
        assertTrue(c.status(runId=a.runId).quiescent)
        assertNotNull(c.status(runId=a.runId).persistenceWarning)
        assertEquals("IdentityPersistenceFailed",assertFailsWith<RpcFailure>{c.start("new","config",limits)}.code)
        assertEquals(1,b.starts.size)
    }
    @Test fun onlyAnAbsentPreviousProcessCanBeReconciled() {
        val s=Store();val b=Backend()
        val old=AutomationController(s,b,processInstanceId="process-a")
        val run=old.start("r","config",limits)
        val same=AutomationController(s,Backend(),processInstanceId="process-a")
        assertTrue(same.hasUnreleasedOwner());assertTrue(same.reconcilePreviousProcess(true).isEmpty())
        assertFailsWith<RpcFailure>{same.start("new","config",limits)}
        val nextBackend=Backend();val next=AutomationController(s,nextBackend,processInstanceId="process-b")
        assertTrue(next.reconcilePreviousProcess(false).isEmpty());assertTrue(next.hasUnreleasedOwner())
        assertEquals(listOf(run.runId),next.reconcilePreviousProcess(true))
        assertFalse(next.hasUnreleasedOwner());assertEquals(0,next.status(runId=run.runId).completedRuns)
        assertTrue(nextBackend.starts.isEmpty())
        next.start("new","config",limits);assertEquals(1,nextBackend.starts.size)
    }
    @Test fun duplicateStartHasOneNativeExecutor() {
        val b=Backend();val c=controller(backend=b)
        val a=c.start("request","config",limits)
        assertEquals(a,c.start("request","config",limits));assertEquals(1,b.starts.size)
        assertFailsWith<RpcFailure>{c.start("request","different",limits)}
    }
    @Test fun concurrentDuplicateReservationsAreAtomic() {
        val b=Backend();val c=controller(backend=b);val latch=CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(8)
        try {
            val results=(1..24).map {pool.submit<String>{latch.await();c.start("same","config",limits).runId}}
            latch.countDown();assertEquals(setOf("run-1"),results.map {it.get()}.toSet())
            assertEquals(1,b.starts.size)
        } finally {pool.shutdownNow()}
    }
    @Test fun reservationIsPersistedBeforeNativeStart() {
        val s=Store();s.fail=true;val b=Backend();val c=controller(s,b)
        assertEquals("IdentityPersistenceFailed",assertFailsWith<RpcFailure>{c.start("request","config",limits)}.code)
        assertTrue(b.starts.isEmpty())
    }
    @Test fun stoppingIsNotQuiescenceAndNeverStartsAnotherTask() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        val stop=c.control(a.runId,"stop-1","stop")
        assertEquals(RunState.Stopping,stop.state);assertFalse(stop.quiescent)
        assertFailsWith<RpcFailure>{c.start("other","config",limits)}
        b.callbacks.getValue(a.runId).exit("Abort",0)
        assertTrue(c.status(runId=a.runId).quiescent)
        assertEquals(RunState.Cancelled,c.status(runId=a.runId).state)
    }
    @Test fun repeatedControlDoesNotRepeatNativeInput() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        b.callbacks.getValue(a.runId).state(RunState.Running)
        c.control(a.runId,"pause-1","pause");c.control(a.runId,"pause-1","pause")
        assertEquals(listOf(a.runId to "pause"),b.controls)
        assertFailsWith<RpcFailure>{c.control(a.runId,"pause-1","stop")}
    }
    @Test fun pauseDoesNotReleaseOwnershipAndResumeRequiresPaused() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        assertFailsWith<RpcFailure>{c.control(a.runId,"bad","resume")}
        b.callbacks.getValue(a.runId).state(RunState.Paused)
        assertFalse(c.status(runId=a.runId).quiescent)
        c.control(a.runId,"resume-1","resume")
        assertEquals(a.runId to "resume",b.controls.last())
    }
    @Test fun lostDispatchReplyIsUncertainNotFailureRelease() {
        val b=Backend();b.throwOnStart=true;val c=controller(backend=b)
        val a=c.start("req","config",limits)
        assertEquals(RunState.NeedsAttention,a.state);assertFalse(a.quiescent)
        assertEquals(a.runId,c.start("req","config",limits).runId)
        assertEquals(1,b.starts.size)
    }
    @Test fun restartDoesNotReplayAndRetainsOriginalIds() {
        val s=Store();val b=Backend();val c=controller(s,b);val a=c.start("req","config",limits)
        val nextBackend=Backend();val next=controller(s,nextBackend)
        val resumed=next.start("req","config",limits)
        assertEquals(a.runId,resumed.runId);assertEquals("ProcessRestart",resumed.terminalReason)
        assertFalse(resumed.quiescent);assertTrue(nextBackend.starts.isEmpty())
        assertFailsWith<RpcFailure>{next.control(a.runId,"stop","stop")}
    }
    @Test fun deadlineRequestsStopThenWaitsForActualExit() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        c.expire(a.runId)
        assertFalse(c.status(runId=a.runId).quiescent)
        assertEquals(a.runId to "stop",b.controls.single())
        b.callbacks.getValue(a.runId).exit("Abort",0)
        assertEquals(RunState.Failed,c.status(runId=a.runId).state)
        assertEquals("DeadlineExceeded",c.status(runId=a.runId).terminalReason)
    }
    @Test fun configuredStopsAreNotExecutionFailuresOrInventedWins() {
        for(reason in listOf("CEGet","FirstClearRewards","LimitMaterials","LimitCEs","StopAfterThisRun")) {
            val b=Backend(); val c=controller(backend=b)
            val a=c.start("req","config",RunLimits(3,60))
            b.callbacks.getValue(a.runId).exit(reason,1)
            val stopped=c.status(runId=a.runId)
            assertEquals(RunState.NeedsAttention,stopped.state)
            assertEquals(reason,stopped.terminalReason); assertEquals(1,stopped.completedRuns)
            assertTrue(stopped.quiescent)
        }
    }
    @Test fun onlyNativeLimitOutcomeCanSucceed() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        b.callbacks.getValue(a.runId).exit("Unexpected",1,"failed after clear")
        assertEquals(RunState.Failed,c.status(runId=a.runId).state)
        val d=c.start("req2","config",limits);b.callbacks.getValue(d.runId).exit("LimitRuns",1)
        assertEquals(RunState.Succeeded,c.status(runId=d.runId).state)
    }
    @Test fun apExhaustionSuccessIsExplicitAndBounded() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",RunLimits(100,60,true))
        b.callbacks.getValue(a.runId).exit("APRanOut",3)
        assertEquals(RunState.Succeeded,c.status(runId=a.runId).state)
        assertEquals(3,c.status(runId=a.runId).completedRuns)
    }
    @Test fun terminalCallbackIsOnceAndLateEventsDoNotChangeIt() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        b.callbacks.getValue(a.runId).exit("LimitRuns",1)
        val done=c.status(runId=a.runId)
        b.callbacks.getValue(a.runId).exit("Unexpected",0);b.callbacks.getValue(a.runId).state(RunState.Running)
        assertEquals(done,c.status(runId=a.runId))
    }
    @Test fun consentRejectionDoesNotRunAndIsStillIdempotent() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        b.callbacks.getValue(a.runId).rejected("RequiresUserConsent","capture unavailable")
        val denied=c.status(runId=a.runId)
        assertEquals(RunState.RequiresUserConsent,denied.state);assertTrue(denied.quiescent)
        assertEquals(denied,c.start("req","config",limits));assertEquals(1,b.starts.size)
    }
    @Test fun busyNativeUiIsNeverClaimedQuiescent() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        b.callbacks.getValue(a.runId).rejected("Busy","manual runner active")
        assertFalse(c.status(runId=a.runId).quiescent)
    }
    @Test fun oldStartIdentitiesSurviveHistoryCompaction() {
        val b=Backend();val c=controller(backend=b)
        repeat(160) { n -> val a=c.start("req$n","config",limits);b.callbacks.getValue(a.runId).exit("LimitRuns",1) }
        c.start("req0","config",limits);assertEquals(160,b.starts.size)
    }
    @Test fun eventCursorExplicitlyMarksMissingRetainedHistory() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        repeat(600){c.nativeEvent(a.runId,"log","test$it")}
        val events=c.events(a.runId,0)
        assertTrue(events.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(events.getValue("events").jsonArray.size in 1..512)
        assertFalse(c.status(runId=a.runId).quiescent)
    }
    @Test fun journalCorruptionIsNotTreatedAsAnEmptyQueue() {
        val dir=Files.createTempDirectory("fga-journal-test").toFile()
        try {
            val file=java.io.File(dir,"runs.json");file.writeText("{broken")
            assertFails { AutomationController(fileStore(file),Backend()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun fileJournalRoundTripPreservesReservation() {
        val dir=Files.createTempDirectory("fga-journal-test").toFile()
        try {
            val store=fileStore(java.io.File(dir,"runs.json"))
            val c=AutomationController(store,Backend());val a=c.start("req","config",limits)
            assertEquals(a,store.load().runs.single())
        } finally { dir.deleteRecursively() }
    }
    @Test fun malformedProtocolAndRawInputsAreRejected() {
        val b=Backend();val dispatcher=RpcDispatcher(controller(backend=b),{buildJsonObject{}},{buildJsonObject{}})
        for(text in listOf("{}","{\"protocolVersion\":2,\"op\":\"start\"}","{\"protocolVersion\":1,\"op\":\"tap\"}")) {
            assertFalse(Json.parseToJsonElement(dispatcher.request(text)).jsonObject.getValue("ok").jsonPrimitive.boolean)
        }
        assertTrue(b.starts.isEmpty())
    }
    @Test fun unboundedOrPaidResourcePayloadDoesNotDispatch() {
        val b=Backend();val c=controller(backend=b)
        assertFails {c.start("req","config",RunLimits(0,60))}
        assertFails {c.start("req","config",RunLimits(1,Long.MAX_VALUE))}
        val rpc=RpcDispatcher(c,{buildJsonObject{}},{buildJsonObject{}})
        assertFalse(Json.parseToJsonElement(rpc.request("""{"protocolVersion":1,"op":"start","requestId":"req","configId":"config","limits":{"maxRuns":1,"timeoutSeconds":60,"sq":1}}""")).jsonObject.getValue("ok").jsonPrimitive.boolean)
        assertTrue(b.starts.isEmpty())
    }
    @Test fun identicalPendingPauseRequestsCoalesceAndStopSupersedesThem() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        b.callbacks.getValue(a.runId).state(RunState.Running)
        c.control(a.runId,"p1","pause");c.control(a.runId,"p2","pause")
        assertEquals(1,b.controls.size)
        c.control(a.runId,"s1","stop")
        assertFalse(c.permitControl(a.runId,"pause"));assertTrue(c.permitControl(a.runId,"stop"))
        b.callbacks.getValue(a.runId).state(RunState.Paused)
        assertEquals(RunState.Stopping,c.status(runId=a.runId).state)
    }
    @Test fun stopIsAvailableAfterPauseReceiptCapacityAndCleanupFailure() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        val callback=b.callbacks.getValue(a.runId);callback.state(RunState.Running)
        repeat(128) { n ->
            c.control(a.runId,"p$n","pause");callback.state(RunState.Paused)
            c.control(a.runId,"r$n","resume");callback.state(RunState.Running)
        }
        repeat(300) { n -> c.control(a.runId,"extra$n","resume") }
        c.control(a.runId,"stop","stop")
        assertEquals("stop",b.controls.last().second)
        c.uncertain(a.runId,"NativeCleanupUnconfirmed")
        c.control(a.runId,"retry-stop","stop")
        assertEquals(RunState.Stopping,c.status(runId=a.runId).state)
        assertTrue(c.status(runId=a.runId).commands.size>272)
    }
    @Test fun staleOldRunCallbackCannotPoisonNewRun() {
        val b=Backend();val c=controller(backend=b);val old=c.start("old","config",limits)
        b.callbacks.getValue(old.runId).exit("LimitRuns",1)
        val next=c.start("next","config",limits)
        c.uncertain(old.runId,"LateCleanup")
        assertEquals(RunState.Starting,c.status(runId=next.runId).state)
        assertFalse(c.permitControl(old.runId,"stop"))
    }
    @Test fun sameProcessUncertaintyStillHonorsDeadlineStop() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        c.uncertain(a.runId,"NativeControlUnconfirmed");c.expire(a.runId)
        assertEquals(a.runId to "stop",b.controls.single())
        assertFalse(c.status(runId=a.runId).quiescent)
    }
    @Test fun nativeDefeatOutcomeIsPreservedWithoutInventingWins() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        b.callbacks.getValue(a.runId).exit("WithdrawDisabled",0,null,"defeated",0)
        val done=c.status(runId=a.runId)
        assertEquals(RunState.Failed,done.state);assertEquals("defeated",done.outcome)
        assertEquals(0,done.completedRuns)
    }
    @Test fun directorySyncFailurePreventsNativeDispatch() {
        val dir=Files.createTempDirectory("fga-journal-sync").toFile()
        try {
            val b=Backend();val c=AutomationController(FileJournalStore(java.io.File(dir,"runs.json")) { error("fsync failed") },b)
            assertFails { c.start("req","config",limits) };assertTrue(b.starts.isEmpty())
        } finally {dir.deleteRecursively()}
    }

    @Test fun coalescedMutationIdentitiesCannotReplayLater() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        val cb=b.callbacks.getValue(a.runId);cb.state(RunState.Running)
        c.control(a.runId,"a","pause");c.control(a.runId,"b","pause");cb.state(RunState.Paused)
        c.control(a.runId,"c","resume");cb.state(RunState.Running)
        c.control(a.runId,"b","pause")
        assertEquals(listOf(a.runId to "pause",a.runId to "resume"),b.controls)
        assertFailsWith<RpcFailure>{c.control(a.runId,"b","stop")}
    }

    @Test fun untilApEmptyCeilingIsNotApExhaustionSuccess() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",RunLimits(2,60,true))
        b.callbacks.getValue(a.runId).exit("LimitRuns",2,null,"target_reached",0)
        val done=c.status(runId=a.runId)
        assertEquals(RunState.NeedsAttention,done.state);assertTrue(done.quiescent)
        assertEquals("LimitRuns",done.terminalReason);assertEquals(2,done.completedRuns)
    }

    @Test fun escapedLargeEventsPageBelowBinderCapacityWithoutSkippingSequences() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        repeat(30){c.nativeEvent(a.runId,"log","\u0001".repeat(4096))}
        var cursor=0L;val sequences=mutableListOf<Long>()
        do {
            val response=c.events(a.runId,cursor)
            assertTrue(response.toString().length<40*1024)
            sequences+=response.getValue("events").jsonArray.map{it.jsonObject.getValue("sequence").jsonPrimitive.long}
            cursor=response.getValue("nextSequence").jsonPrimitive.long
        } while(response.getValue("hasMore").jsonPrimitive.boolean)
        assertEquals((1L..31L).toList(),sequences)
    }

    @Test fun busyManualRunnerCanResolveOnlyAfterProvenIdleCallback() {
        val b=Backend();val c=controller(backend=b);val a=c.start("req","config",limits)
        val cb=b.callbacks.getValue(a.runId);cb.rejected("Busy","Manual native run owns the executor")
        assertFalse(c.status(runId=a.runId).quiescent)
        assertFailsWith<RpcFailure>{c.control(a.runId,"stop","stop")}
        cb.exit("Busy",0)
        val done=c.status(runId=a.runId)
        assertTrue(done.quiescent);assertFalse(done.reconciliationRequired)
        c.start("next","config",limits);assertEquals(2,b.starts.size)
    }

}
