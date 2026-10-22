package io.github.fga.automation

import kotlinx.serialization.json.*
import kotlin.test.*
import java.nio.file.Files

class ExternalPlanProtocolTest {
    class Store:ExternalJournalStore {
        var value=ExternalJournal();var fail=false
        override fun load()=value
        override fun save(journal:ExternalJournal) { check(!fail) { "disk full" };value=journal }
    }
    private var clock=1000L
    private var permitted=true
    private var sequence=0
    private fun protocol(store:Store=Store())=ExternalPlanProtocol(store,{ if(!permitted) throw RpcFailure("NotOwned","stopped or paused") },{clock},{"obs-${++sequence}"})
    private val context=buildJsonObject { put("battle",1);put("turn",1);put("wave",1);put("waveTurn",1);put("phase","skills") }
    private val frame=buildJsonObject { put("uri","content://test.automation/frames/token");put("width",1280);put("height",720);put("sha256","a".repeat(64)) }
    private fun ready(p:ExternalPlanProtocol)=p.apply {open("run")}.publish("run",context,frame)
    private fun plan(r:ExternalReady,id:String="plan",action:JsonObject=buildJsonObject { put("type","skills");put("actions",JsonArray(emptyList())) })=
        ExternalPlan(r.runId,id,r.battleId,r.turnId,r.phase,r.observationId,action)
    @Test fun repeatedReadsAreNonConsumingAndDuplicateSubmitDoesNotDispatch() {
        val p=protocol();val r=ready(p)
        assertEquals(p.next("run"),p.next("run"))
        val receipt=p.submit(plan(r))
        assertEquals("Accepted",receipt.status)
        assertEquals(receipt,p.submit(plan(r)))
        assertEquals(receipt,p.accepted("run"))
        assertFailsWith<RpcFailure>{ p.submit(plan(r,"second")) }
        assertEquals("Accepted",p.status("run","plan").status)
    }
    @Test fun changedPayloadConflictsAcrossTerminalAndStoppedStates() {
        val p=protocol();val r=ready(p);p.submit(plan(r));p.executing("run","plan");p.completed("run","plan");p.close("run","Abort")
        permitted=false
        assertEquals("Completed",p.submit(plan(r)).status)
        assertFailsWith<RpcFailure>{p.submit(plan(r).copy(observationId="other"))}
    }
    @Test fun staleTurnPhaseAndObservationRejectBeforeAcceptance() {
        val p=protocol();val r=ready(p)
        listOf(plan(r).copy(turnId="old"),plan(r).copy(battleId="old"),plan(r).copy(observationId="old"),
            plan(r).copy(phase="cards",action=buildJsonObject {put("type","cards");put("order",JsonArray(listOf(1,2,3).map(::JsonPrimitive)))})).forEach {
            assertFailsWith<RpcFailure>{p.submit(it)}
        }
        assertNull(p.accepted("run"))
    }
    @Test fun retainedObservationDoesNotExpireWhileTheOwnedTaskStillPermitsIt() {
        val p=protocol();val r=ready(p)
        assertNull(r.expiresAtMillis)
        clock += 3_600_000
        assertEquals("Accepted",p.submit(plan(r)).status)
        clock += 3_600_000
        assertEquals("Executing",p.executing("run","plan").status)
        p.completed("run","plan")
        assertEquals("Completed",p.status("run","plan").status)
        assertEquals("unverified",p.status("run","plan").gameEffect)
    }
    @Test fun stopOrPausePreventsDispatchOfAnAlreadyAcceptedAction() {
        val p=protocol();val r=ready(p);p.submit(plan(r));permitted=false
        assertFailsWith<RpcFailure>{p.executing("run","plan")}
        assertEquals("Accepted",p.status("run","plan").status)
        p.close("run","Abort");assertEquals("Rejected",p.status("run","plan").status)
    }
    @Test fun persistenceFailureCannotAdmitEffectOrAcknowledgeCompletion() {
        val store=Store();val p=protocol(store);val r=ready(p)
        store.fail=true;assertFailsWith<IllegalStateException>{p.submit(plan(r))};assertNull(p.accepted("run"))
        store.fail=false;p.submit(plan(r));store.fail=true
        assertFailsWith<IllegalStateException>{p.executing("run","plan")};assertEquals("Accepted",p.status("run","plan").status)
        store.fail=false;p.executing("run","plan");store.fail=true
        assertFailsWith<IllegalStateException>{p.completed("run","plan")};assertEquals("Executing",p.status("run","plan").status)
    }
    @Test fun executingReceiptIsDurableAndCannotReplay() {
        val store=Store();val p=protocol(store);val r=ready(p);p.submit(plan(r));p.executing("run","plan")
        assertEquals("Executing",store.value.sessions.single().receipts.single().status)
        assertNull(p.next("run")["ready"].takeUnless { it==JsonNull })
        assertFailsWith<RpcFailure>{p.executing("run","plan")}
        p.failed("run","plan","native ambiguous");assertEquals("Unconfirmed",p.status("run","plan").status)
        assertFailsWith<RpcFailure>{p.publish("run",context,frame)}
    }
    @Test fun restartNeverReplaysAcceptedOrExecutingAndRetainsOriginalIds() {
        for(executing in listOf(false,true)) {
            val store=Store();val p=protocol(store);val r=ready(p);p.submit(plan(r));if(executing)p.executing("run","plan")
            val restarted=protocol(store)
            assertEquals("Unconfirmed",restarted.status("run","plan").status)
            assertEquals("requires_reconciliation",restarted.next("run").getValue("decisionState").jsonPrimitive.content)
            assertNull(restarted.accepted("run"));assertFailsWith<RpcFailure>{restarted.open("run")}
        }
    }
    @Test fun completedSkillCannotReuseObservationButParentIsOptionalMetadata() {
        val p=protocol();val r=ready(p);p.submit(plan(r));p.executing("run","plan");p.completed("run","plan")
        val cards=JsonObject(context+mapOf("phase" to JsonPrimitive("cards")))
        val withoutParent=p.publish("run",cards,frame)
        assertNull(withoutParent.beforeAttackFrame)
        val next=p.publish("run",cards,frame,frame)
        assertNotEquals(r.observationId,next.observationId);assertEquals(r.turnId,next.turnId)
        assertEquals(frame,next.beforeAttackFrame)
        assertFailsWith<RpcFailure>{p.submit(plan(r,"stale"))}
    }
    @Test fun onlyExplicitBoundedActionsAreAccepted() {
        val p=protocol();val r=ready(p)
        listOf("tap","refill","summon","auto","spam").forEach { type ->
            assertFailsWith<RpcFailure>{p.submit(plan(r,action=buildJsonObject {put("type",type)}))}
        }
        assertFailsWith<RpcFailure>{p.submit(plan(r,action=buildJsonObject {put("type","enemy_target");put("index",5);put("enemyCount",6)}))}
        assertFailsWith<RpcFailure>{p.submit(plan(r,action=buildJsonObject {put("type","finish_skills");put("fallback",true)}))}
        assertNull(p.accepted("run"))
    }
    @Test fun nativeCloseNeverReportsExecutingAsCompleted() {
        val p=protocol();val r=ready(p);p.submit(plan(r));p.executing("run","plan");p.close("run","Abort")
        assertEquals("Unconfirmed",p.status("run","plan").status)
        assertEquals("unverified",p.status("run","plan").gameEffect)
    }
    @Test fun directCardsDoNotRequireHostReaderOrGameEffectAttestation() {
        val p=protocol();p.open("run")
        val r=p.publish("run",JsonObject(context+mapOf("phase" to JsonPrimitive("cards"))),frame)
        val action=buildJsonObject {put("type","cards");put("order",JsonArray(listOf(6,7,8).map(::JsonPrimitive)))}
        assertEquals("Accepted",p.submit(plan(r,action=action)).status)
        assertEquals("Executing",p.executing("run","plan").status)
        p.completed("run","plan")
        assertEquals("Completed",p.status("run","plan").status)
        assertEquals("unverified",p.status("run","plan").gameEffect)
        val next=p.publish("run",JsonObject(context+mapOf("phase" to JsonPrimitive("cards"))),frame)
        val legacy=JsonObject(action+mapOf("initialHandEvidence" to buildJsonObject {put("complete",false)}))
        assertEquals("Accepted",p.submit(plan(next,"next",legacy)).status)
    }
    @Test fun explicitEnemyTargetBelongsToTheSameCardPlan() {
        val p=protocol();p.open("run")
        val r=p.publish("run",JsonObject(context+mapOf("phase" to JsonPrimitive("cards"))),frame)
        val action=buildJsonObject { put("type","cards");put("target",2);put("order",JsonArray(listOf(1,2,3).map(::JsonPrimitive))) }
        assertEquals("Accepted",p.submit(plan(r,action=action)).status)
    }
    @Test fun singleActionsCannotReenterPerSkillObservationFlow() {
        val p=protocol();val r=ready(p)
        for(type in listOf("finish_skills","enemy_target","servant_skill","observe")) {
            assertFailsWith<RpcFailure>{ p.submit(plan(r,action=buildJsonObject {put("type",type)})) }
        }
        val batch=buildJsonObject {
            put("type","skills");put("delayMillis",1500)
            putJsonArray("actions") { add(buildJsonObject {put("type","servant_skill");put("slot",0);put("index",2);put("target",1)}) }
        }
        assertEquals("Accepted",p.submit(plan(r,action=batch)).status)
    }
    @Test fun exactCardWireShapeRejectsTruncationDuplicatesAndInvalidIndices() {
        val p=protocol();p.open("run")
        val r=p.publish("run",JsonObject(context+mapOf("phase" to JsonPrimitive("cards"))),frame)
        for (order in listOf(listOf(1,2),listOf(1,2,3,4),listOf(1,1,2),listOf(1,2,9))) {
            val action=buildJsonObject {put("type","cards");put("order",JsonArray(order.map(::JsonPrimitive)))}
            assertFailsWith<IllegalArgumentException>{p.submit(plan(r,action=action))}
        }
        assertNull(p.accepted("run"))
    }
    @Test fun fileJournalRoundTripSyncsBeforeAcknowledgement() {
        val directory=Files.createTempDirectory("external-plan-test").toFile()
        try {
            var sync=0
            val store=FileExternalJournalStore(java.io.File(directory,"plans.json")) { sync++ }
            val p=ExternalPlanProtocol(store,{}, {clock},{"observation"});val r=ready(p);p.submit(plan(r));p.executing("run","plan")
            assertEquals(4,sync)
            val restarted=ExternalPlanProtocol(store,{})
            assertEquals("Unconfirmed",restarted.status("run","plan").status)
        } finally {directory.deleteRecursively()}
    }
    @Test fun startModeIsPinnedInRequestIdempotencyAndSnapshot() {
        val store=AutomationControllerTest.Store();val backend=AutomationControllerTest.Backend()
        val c=AutomationController(store,backend,newId={"run"})
        val run=c.start("request","profile",RunLimits(1,60),"smart")
        assertEquals("smart",run.decisionMode);assertEquals("smart",backend.starts.single().decisionMode)
        assertFailsWith<RpcFailure>{c.start("request","profile",RunLimits(1,60),"script")}
    }
    @Test fun concurrentPlansForOneObservationAcceptExactlyOneIdentity() {
        val p=protocol();val r=ready(p)
        val latch=java.util.concurrent.CountDownLatch(1)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results=(1..24).map { id -> pool.submit<Boolean> {
                latch.await()
                try {p.submit(plan(r,"plan-$id"));true} catch(_:RpcFailure) {false}
            }}
            latch.countDown()
            assertEquals(1,results.count {it.get()})
        } finally {pool.shutdownNow()}
    }
    @Test fun rpcDispatchesOnlyVersionedSemanticPlanSurface() {
        val controller=AutomationController(AutomationControllerTest.Store(),AutomationControllerTest.Backend())
        var calls=0
        val rpc=RpcDispatcher(controller,{buildJsonObject{}},{buildJsonObject{}}) { op,q ->
            calls++;assertEquals("decision.next",op);assertEquals("run",q.getValue("runId").jsonPrimitive.content)
            buildJsonObject {put("runId","run");put("decisionState","waiting")}
        }
        val reply=Json.parseToJsonElement(rpc.request("""{"protocolVersion":1,"op":"decision.next","runId":"run"}""")).jsonObject
        assertTrue(reply.getValue("ok").jsonPrimitive.boolean)
        assertEquals(1,calls)
        val raw=Json.parseToJsonElement(rpc.request("""{"protocolVersion":1,"op":"tap","runId":"run","x":1}""")).jsonObject
        assertFalse(raw.getValue("ok").jsonPrimitive.boolean);assertEquals(1,calls)
    }
    @Test fun platformDispatchIsExplicitAndNeverStartsABattle() {
        val controller=AutomationController(AutomationControllerTest.Store(),AutomationControllerTest.Backend())
        val calls=mutableListOf<String>()
        val rpc=RpcDispatcher(controller,{buildJsonObject{}},{buildJsonObject{}},platform={op,_ ->
            calls.add(op);buildJsonObject {put("accepted",true)}
        })
        for(op in listOf("platform.status","platform.prepare","platform.close","platform.shizuku.request",
            "platform.accessibility.enable","platform.accessibility.disable")) {
            val reply=Json.parseToJsonElement(rpc.request("""{"protocolVersion":1,"op":"$op"}""")).jsonObject
            assertTrue(reply.getValue("ok").jsonPrimitive.boolean)
        }
        assertEquals(6,calls.size)
        val arbitrary=Json.parseToJsonElement(rpc.request("""{"protocolVersion":1,"op":"platform.shell"}""")).jsonObject
        assertFalse(arbitrary.getValue("ok").jsonPrimitive.boolean)
        assertEquals(6,calls.size)
    }
}
