package io.github.fga.automation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** No game logic or coordinates: durable high-level ownership and RPC only. */
@Serializable data class RunLimits(val maxRuns:Int, val timeoutSeconds:Long, val stopOnApEmpty:Boolean=false) {
    fun validate() {
        require(maxRuns in 1..100) { "maxRuns must be 1..100" }
        require(timeoutSeconds in 10..7200) { "timeoutSeconds must be 10..7200" }
    }
}
@Serializable enum class RunState { Starting, Running, Paused, Stopping, Succeeded, Cancelled, Failed, RequiresUserConsent, NeedsAttention }
@Serializable data class RunEvent(val sequence:Long, val type:String, val state:RunState, val recordedAtMillis:Long, val detail:String?=null)
@Serializable data class CommandReceipt(val requestId:String,val op:String)
@Serializable data class RunSnapshot(
    val requestId:String, val runId:String, val configId:String, val limits:RunLimits,
    val state:RunState, val quiescent:Boolean, val completedRuns:Int=0,
    val terminalReason:String?=null, val error:String?=null,
    val stopReason:String?=null, val pendingControl:String?=null,
    val reconciliationRequired:Boolean=false, val outcome:String?=null, val withdrawCount:Int=0,
    val createdAtMillis:Long, val updatedAtMillis:Long,
    val events:List<RunEvent> = emptyList(), val commands:List<CommandReceipt> = emptyList(),
    val decisionMode:String="script",
) {
    val active get() = !quiescent
}
@Serializable data class Journal(val schemaVersion:Int=1,val runs:List<RunSnapshot> = emptyList())

interface JournalStore { fun load():Journal; fun save(journal:Journal) }

/** Flush before dispatch, then same-directory atomic rename on Android/macOS/Linux. */
class FileJournalStore(private val file:File, private val syncDirectory:(File)->Unit):JournalStore {
    override fun load():Journal = if(file.exists()) Json.decodeFromString(file.readText()) else Journal()
    override fun save(journal:Journal) {
        file.parentFile?.mkdirs()
        val temp=File(file.parentFile,file.name+".tmp")
        FileOutputStream(temp).use { it.write(Json.encodeToString(journal).toByteArray());it.fd.sync() }
        check(temp.renameTo(file)) { "Cannot commit automation journal" }
        syncDirectory(requireNotNull(file.absoluteFile.parentFile)) // durable metadata before native dispatch
    }
}

interface NativeBattleBackend {
    fun start(run:RunSnapshot, callback:NativeBattleCallback)
    fun control(runId:String, op:String)
}
interface NativeBattleCallback {
    fun state(state:RunState)
    fun exit(reason:String, completedRuns:Int, error:String?=null, outcome:String?=null, withdrawCount:Int=0)
    fun rejected(code:String,message:String)
}
class RpcFailure(val code:String, override val message:String):RuntimeException(message)

/** One controller per Android process. It never interprets a screen as a victory. */
class AutomationController(
    private val store:JournalStore,
    private val backend:NativeBattleBackend,
    private val now:()->Long={System.currentTimeMillis()},
    private val newId:()->String={UUID.randomUUID().toString()},
) {
    private var journal=store.load().also { require(it.schemaVersion==1) { "Unsupported journal schema" } }
    init {
        // A process restart is NOT a resume. Preserve ambiguity and the same IDs.
        if(journal.runs.any { it.active }) {
            journal=journal.copy(runs=journal.runs.map {
                if(it.active) event(it.copy(state=RunState.NeedsAttention,terminalReason="ProcessRestart",reconciliationRequired=true,error="Previous native executor exit is unconfirmed"),"process_restart") else it
            })
            store.save(journal)
        }
    }
    private fun event(run:RunSnapshot,type:String,detail:String?=null):RunSnapshot = run.copy(
        updatedAtMillis=now(), events=(run.events+RunEvent((run.events.lastOrNull()?.sequence ?: 0)+1,type,run.state,now(),detail)).takeLast(512))
    private fun commit(run:RunSnapshot) {
        val others=journal.runs.filterNot { it.runId==run.runId }
        // Keep request identities forever within the bounded journal capacity;
        // evicting IDs would allow a delayed duplicate to start another battle.
        val next=journal.copy(runs=(others+run).mapIndexed { index,item ->
            if(index<others.size-64 && item.quiescent) item.copy(events=item.events.takeLast(1)) else item
        })
        store.save(next);journal=next
    }
    @Synchronized fun status(runId:String?=null,requestId:String?=null):RunSnapshot {
        if((runId==null)==(requestId==null)) throw RpcFailure("InvalidRequest","Provide exactly one runId or requestId")
        return journal.runs.firstOrNull { if(runId!=null) it.runId==runId else it.requestId==requestId }
            ?: throw RpcFailure(if(runId!=null) "UnknownRun" else "UnknownRequest","No retained matching task")
    }
    @Synchronized fun start(requestId:String,configId:String,limits:RunLimits,decisionMode:String="script"):RunSnapshot {
        identifier(requestId);identifier(configId);limits.validate()
        if(decisionMode !in setOf("script","smart")) throw RpcFailure("InvalidDecisionMode","decisionMode must be script or smart")
        journal.runs.firstOrNull { it.requestId==requestId }?.let {
            if(it.configId!=configId || it.limits!=limits || it.decisionMode!=decisionMode) throw RpcFailure("IdempotencyConflict","requestId payload changed")
            return it
        }
        if(journal.runs.any { it.active }) throw RpcFailure("Busy","An executor owns control or its exit is unconfirmed")
        if(journal.runs.size>=10_000) throw RpcFailure("JournalCapacity","Archive with retained idempotency tombstones before accepting more tasks")
        val run=event(RunSnapshot(requestId,newId(),configId,limits,RunState.Starting,false,createdAtMillis=now(),updatedAtMillis=now(),decisionMode=decisionMode),"start_accepted")
        commit(run) // Starting and requestId are durable before native side effects.
        try {
            backend.start(run,object:NativeBattleCallback {
                override fun state(state:RunState) { nativeState(run.runId,state) }
                override fun exit(reason:String,completedRuns:Int,error:String?,outcome:String?,withdrawCount:Int) { nativeExit(run.runId,reason,completedRuns,error,outcome,withdrawCount) }
                override fun rejected(code:String,message:String) { nativeRejected(run.runId,code,message) }
            })
        } catch(e:Exception) {
            // Backend may have started before the transport/adapter threw.
            uncertain(run.runId,"DispatchUncertain",e.message)
        }
        return status(runId=run.runId)
    }
    @Synchronized fun control(runId:String,requestId:String,op:String):RunSnapshot {
        identifier(requestId)
        if(op !in setOf("pause","resume","stop")) throw RpcFailure("InvalidOperation","Unsupported control")
        var run=status(runId=runId)
        run.commands.firstOrNull { it.requestId==requestId }?.let {
            if(it.op!=op) throw RpcFailure("IdempotencyConflict","Control requestId payload changed")
            return run
        }
        if(run.quiescent) return run
        if(run.reconciliationRequired || (run.state==RunState.NeedsAttention && op!="stop"))
            throw RpcFailure("RequiresReconciliation","Native ownership must be reconciled before this control")
        // Every accepted mutation identity is retained, including coalesced
        // commands. Reserve separate capacity for stop and cleanup retries.
        val capacity=if(op=="stop") 272 else 256
        if(run.commands.size>=capacity) throw RpcFailure("CommandLimit",if(op=="stop") "Stop retry receipt capacity exhausted; reconcile retained attempts" else "Pause/resume receipt capacity exhausted; stop remains available")
        if(run.pendingControl==op || (op=="pause" && run.state==RunState.Paused) ||
            (op=="resume" && run.state==RunState.Running && run.pendingControl==null)) {
            run=event(run.copy(commands=run.commands+CommandReceipt(requestId,op)),op+"_coalesced")
            commit(run);return run
        }
        if(op!="stop" && run.pendingControl!=null) throw RpcFailure("InvalidState","Another control is pending")
        if(op=="resume" && run.state!=RunState.Paused) throw RpcFailure("InvalidState","Only an explicitly paused run can resume")
        if(op=="pause" && run.state!=RunState.Running) throw RpcFailure("InvalidState","Only a running task can pause")
        run=event(run.copy(state=if(op=="stop") RunState.Stopping else run.state, pendingControl=op,
                          commands=run.commands+CommandReceipt(requestId,op)),op+"_accepted")
        commit(run)
        try { backend.control(runId,op) }
        catch(e:Exception) { uncertain(runId,"ControlUncertain",e.message) }
        return status(runId=runId)
    }
    @Synchronized private fun nativeState(id:String,state:RunState) {
        val run=status(runId=id)
        if(run.quiescent || run.state in setOf(RunState.Stopping,RunState.NeedsAttention)) return
        if(state !in setOf(RunState.Starting,RunState.Running,RunState.Paused,RunState.Stopping)) return
        val acknowledged=(run.pendingControl=="pause" && state==RunState.Paused) ||
            (run.pendingControl=="resume" && state==RunState.Running)
        commit(event(run.copy(state=state,pendingControl=if(acknowledged) null else run.pendingControl),"native_state"))
    }
    @Synchronized private fun nativeRejected(id:String,code:String,message:String) {
        val run=status(runId=id)
        if(run.quiescent) return
        val busy=code=="Busy"
        commit(event(run.copy(state=if(code=="RequiresUserConsent") RunState.RequiresUserConsent else if(busy) RunState.NeedsAttention else RunState.Failed,
            quiescent=!busy,reconciliationRequired=busy,terminalReason=code,error=message),"start_rejected"))
    }
    @Synchronized private fun nativeExit(id:String,reason:String,count:Int,error:String?,outcome:String?,withdrawCount:Int) {
        val run=status(runId=id)
        if(run.quiescent) return
        val state=when {
            run.stopReason!=null -> RunState.Failed
            reason=="LimitRuns" && count>=run.limits.maxRuns && !run.limits.stopOnApEmpty -> RunState.Succeeded
            reason=="APRanOut" && run.limits.stopOnApEmpty -> RunState.Succeeded
            reason=="RequiresUserConsent" -> RunState.RequiresUserConsent
            reason=="Abort" || reason=="CancelledBeforeStart" -> RunState.Cancelled
            error==null && reason in setOf("CEGet","LimitCEs","FirstClearRewards","LimitMaterials",
                "StopAfterThisRun","InventoryFull","SupportSelectionManual","SupportSelectionPreferredNotSet","LimitRuns","APRanOut") ->
                if(!run.limits.stopOnApEmpty && count>=run.limits.maxRuns) RunState.Succeeded else RunState.NeedsAttention
            else -> RunState.Failed
        }
        commit(event(run.copy(state=state,quiescent=true,reconciliationRequired=false,pendingControl=null,completedRuns=count.coerceAtLeast(0),outcome=if(reason=="LimitRuns" && run.limits.stopOnApEmpty) "run_ceiling" else outcome,withdrawCount=withdrawCount.coerceAtLeast(0),terminalReason=run.stopReason ?: reason,error=error ?: if(reason=="LimitRuns" && run.limits.stopOnApEmpty) "Finite run ceiling reached before AP exhaustion" else null),"native_exit",reason))
    }
    @Synchronized fun expire(id:String) {
        val run=status(runId=id)
        if(!run.active || run.reconciliationRequired || (run.stopReason=="DeadlineExceeded" && run.pendingControl=="stop")) return
        commit(event(run.copy(state=RunState.Stopping,stopReason="DeadlineExceeded",pendingControl="stop"),"deadline_stop_requested"))
        try { backend.control(id,"stop") }
        catch(e:Exception) { uncertain(id,"DeadlineStopUnconfirmed",e.message) }
    }
    @Synchronized fun nativeEvent(id:String,type:String,detail:String) {
        val run=status(runId=id)
        if(run.active && run.state!=RunState.NeedsAttention) commit(event(run,type,detail.take(4096)))
    }
    /** A queued control must still belong to this run when the Android main
     * looper dispatches it; a later stop supersedes pending pause/resume. */
    @Synchronized fun permitControl(id:String,op:String):Boolean {
        val run=status(runId=id)
        return run.active && !run.reconciliationRequired && run.pendingControl==op
    }
    @Synchronized fun uncertain(id:String,reason:String,error:String?=null) {
        val run=status(runId=id)
        if(run.active) commit(event(run.copy(state=RunState.NeedsAttention,terminalReason=reason,error=error,pendingControl=null),"executor_unavailable"))
    }
    @Synchronized fun uncertainActive(reason:String) {
        journal.runs.lastOrNull { it.active }?.let { uncertain(it.runId,reason) }
    }
    fun snapshotJson(run:RunSnapshot):JsonObject = Json.encodeToJsonElement(run).jsonObject.let { obj ->
        JsonObject(obj.filterKeys { it !in setOf("events","commands") })
    }
    @Synchronized fun events(runId:String,after:Long):JsonObject {
        require(after>=0)
        val run=status(runId=runId);val first=run.events.firstOrNull()?.sequence ?: 1
        // Bound the encoded String well below Binder transaction capacity,
        // including UTF-16 parcel and JSON escaping overhead.
        val rows=run.events.filter { it.sequence>after }
        var encodedChars=0
        val page=rows.takeWhile {
            val size=Json.encodeToString(it).length
            (encodedChars+size<=32*1024).also { accepted -> if(accepted) encodedChars+=size }
        }
        return buildJsonObject {
            put("runId",runId);put("earliestSequence",first);put("truncated",after<first-1)
            put("nextSequence",page.lastOrNull()?.sequence ?: after)
            put("hasMore",page.size<rows.size)
            put("events",Json.encodeToJsonElement(page))
        }
    }
    companion object {
        fun identifier(value:String) {
            if(!value.matches(Regex("[A-Za-z0-9_.:-]{1,160}"))) throw RpcFailure("InvalidIdentifier","Invalid identifier")
        }
    }
}

/** Provider must check the Binder caller UID BEFORE calling this decoder. */
class RpcDispatcher(private val controller:AutomationController,private val capabilities:()->JsonObject,private val configs:()->JsonObject,
    private val platform:((String,JsonObject)->JsonObject)?=null,
    private val diagnostic:(()->JsonObject)?=null,
    private val externalPlans:((String,JsonObject)->JsonObject)?=null) {
    fun request(text:String):String = try {
        if(text.toByteArray().size>65536) throw RpcFailure("RequestTooLarge","Request exceeds 64KiB")
        val q=Json.parseToJsonElement(text).jsonObject
        val allowed=setOf("protocolVersion","op","requestId","runId","configId","limits","afterSequence","decisionMode",
            "planId","battleId","turnId","phase","observationId","action","mode")
        if(q.keys.any { it !in allowed }) throw RpcFailure("InvalidRequest","Unknown request field")
        if(q["protocolVersion"]?.jsonPrimitive?.int!=1) throw RpcFailure("ProtocolMismatch","protocolVersion must be 1")
        fun s(k:String)=q[k]?.jsonPrimitive?.content ?: throw RpcFailure("InvalidRequest","Missing $k")
        val result=when(val op=s("op")) {
            "capabilities","version" -> capabilities()
            "configs" -> configs()
            "diagnostic.capture" -> diagnostic?.invoke()
                ?: throw RpcFailure("UnsupportedCapability","Native capture diagnostic is unavailable")
            "start" -> controller.snapshotJson(controller.start(s("requestId"),s("configId"),Json.decodeFromJsonElement<RunLimits>(q.getValue("limits")),q["decisionMode"]?.jsonPrimitive?.content ?: "script"))
            "status" -> controller.snapshotJson(controller.status(q["runId"]?.jsonPrimitive?.content,q["requestId"]?.jsonPrimitive?.content))
            "pause","resume","stop" -> controller.snapshotJson(controller.control(s("runId"),s("requestId"),op))
            "events" -> controller.events(s("runId"),q["afterSequence"]?.jsonPrimitive?.long ?: 0)
            "decision.next","decision.submit","decision.status" -> externalPlans?.invoke(op,q)
                ?: throw RpcFailure("UnsupportedCapability","External-plan execution is unavailable")
            "platform.status","platform.prepare","platform.close","platform.shizuku.request",
            "platform.accessibility.enable","platform.accessibility.disable","platform.capture.permission" -> platform?.invoke(op,q)
                ?: throw RpcFailure("UnsupportedCapability","Platform lifecycle is unavailable")
            else -> throw RpcFailure("InvalidOperation","No such high-level operation")
        }
        buildJsonObject { put("ok",true);put("protocolVersion",1);put("result",result) }.toString()
    } catch(e:Exception) {
        buildJsonObject {
            put("ok",false);put("protocolVersion",1)
            putJsonObject("error") { put("code",(e as? RpcFailure)?.code ?: "InvalidRequest");put("message",e.message?.take(512) ?: "Invalid request") }
        }.toString()
    }
}
