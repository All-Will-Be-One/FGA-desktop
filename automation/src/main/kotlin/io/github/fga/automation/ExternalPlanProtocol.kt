package io.github.fga.automation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Durable external-plan mailbox. This layer contains no battle policy. */
@Serializable data class ExternalReady(
    val runId:String,val battleId:String,val turnId:String,val phase:String,val observationId:String,
    val sequence:Long,val context:JsonObject,val frame:JsonObject,val beforeAttackFrame:JsonObject?=null,
    val createdAtMillis:Long,val expiresAtMillis:Long?=null,
)
@Serializable data class ExternalPlan(
    val runId:String,val planId:String,val battleId:String,val turnId:String,val phase:String,
    val observationId:String,val action:JsonObject,
)
@Serializable data class ExternalReceipt(
    val planId:String,val runId:String,val battleId:String,val turnId:String,val phase:String,
    val observationId:String,val action:JsonObject,val status:String,
    val gameEffect:String="unverified",val reason:String?=null,val createdAtMillis:Long,val updatedAtMillis:Long,
)
@Serializable data class ExternalSession(
    val runId:String,val decisionState:String="waiting",val sequence:Long=0,
    val ready:ExternalReady?=null,val receipts:List<ExternalReceipt> = emptyList(),
)
@Serializable data class ExternalJournal(val schemaVersion:Int=1,val sessions:List<ExternalSession> = emptyList())
interface ExternalJournalStore { fun load():ExternalJournal;fun save(journal:ExternalJournal) }
class FileExternalJournalStore(private val file:File,private val syncDirectory:(File)->Unit):ExternalJournalStore {
    override fun load()=if(file.exists()) Json.decodeFromString<ExternalJournal>(file.readText()) else ExternalJournal()
    override fun save(journal:ExternalJournal) {
        file.parentFile?.mkdirs()
        val temp=File(file.parentFile,file.name+".tmp")
        FileOutputStream(temp).use { it.write(Json.encodeToString(journal).toByteArray());it.fd.sync() }
        check(temp.renameTo(file)) { "Cannot commit external-plan journal" }
        syncDirectory(requireNotNull(file.absoluteFile.parentFile))
    }
}

class ExternalPlanProtocol(
    private val store:ExternalJournalStore,
    /** Rechecks task ownership, mode, deadline and control state before accepting/dispatching. */
    private val permit:(String)->Unit,
    private val now:()->Long={System.currentTimeMillis()},
    private val newId:()->String={UUID.randomUUID().toString()},
) {
    private var journal=store.load().also { require(it.schemaVersion==1) }
    init {
        if(journal.sessions.any { it.decisionState !in setOf("ended","requires_reconciliation") }) {
            val next=journal.copy(sessions=journal.sessions.map { session ->
                if(session.decisionState=="ended") session else session.copy(
                    decisionState="requires_reconciliation",ready=null,
                    receipts=session.receipts.map {
                        if(it.status in setOf("Accepted","Executing")) it.copy(status="Unconfirmed",reason="ProcessRestart",updatedAtMillis=now()) else it
                    })
            })
            store.save(next);journal=next
        }
    }
    private fun session(runId:String)=journal.sessions.firstOrNull { it.runId==runId }
        ?: throw RpcFailure("UnknownDecisionRun","No external-plan session")
    private fun commit(session:ExternalSession) {
        val next=journal.copy(sessions=journal.sessions.filterNot { it.runId==session.runId }+session)
        store.save(next);journal=next
    }
    @Synchronized fun open(runId:String) {
        AutomationController.identifier(runId);permit(runId)
        if(journal.sessions.any { it.runId==runId }) throw RpcFailure("DecisionRunAlreadyOpened","A native executor cannot reopen a retained session")
        if(journal.sessions.size>=10_000) throw RpcFailure("JournalCapacity","External-plan session capacity exhausted")
        commit(ExternalSession(runId))
    }
    @Synchronized fun publish(runId:String,context:JsonObject,frame:JsonObject,parent:JsonObject?=null):ExternalReady {
        permit(runId)
        val old=session(runId)
        if(old.decisionState in setOf("ended","requires_reconciliation") || old.receipts.any { it.status in setOf("Accepted","Executing") })
            throw RpcFailure("InvalidDecisionState","An unresolved external action cannot be replaced")
        val battle=context["battle"]?.jsonPrimitive?.int ?: throw RpcFailure("InvalidContext","Missing battle")
        val turn=context["turn"]?.jsonPrimitive?.int ?: throw RpcFailure("InvalidContext","Missing turn")
        val phase=context["phase"]?.jsonPrimitive?.content ?: throw RpcFailure("InvalidContext","Missing phase")
        require(battle>0 && turn>0 && phase in setOf("skills","cards")) { "Invalid native turn context" }
        val sequence=old.sequence+1
        val ready=ExternalReady(runId,"$runId:b$battle","$runId:b$battle:t$turn",phase,newId(),sequence,context,frame,
            if(phase=="cards") parent else null,now())
        commit(old.copy(decisionState="ready",sequence=sequence,ready=ready))
        return ready
    }
    @Synchronized fun next(runId:String):JsonObject {
        val s=session(runId)
        return buildJsonObject {
            put("runId",runId);put("decisionState",s.decisionState)
            put("ready",s.ready?.let {Json.encodeToJsonElement(it)} ?: JsonNull)
            put("receipt",s.receipts.lastOrNull()?.let {Json.encodeToJsonElement(it)} ?: JsonNull)
        }
    }
    private fun validateAction(phase:String,action:JsonObject,nested:Boolean=false) {
        fun integer(key:String,range:IntRange):Int {
            val value=action[key]?.jsonPrimitive?.intOrNull ?: throw RpcFailure("InvalidAction","Missing integer $key")
            if(value !in range) throw RpcFailure("InvalidAction","Out of range $key")
            return value
        }
        fun target() { if(action["target"]!=null && action["target"]!=JsonNull) integer("target",1..3) }
        val type=action["type"]?.jsonPrimitive?.content ?: throw RpcFailure("InvalidAction","Missing action type")
        if(!nested && type !in setOf("skills","cards")) throw RpcFailure("InvalidAction","Expected a complete phase plan")
        val keys=when(type) {
            "servant_skill" -> { require(phase=="skills");integer("slot",0..2);integer("index",0..2);target();setOf("type","slot","index","target") }
            "master_skill" -> { require(phase=="skills");integer("index",0..2);target();setOf("type","index","target") }
            "order_change" -> { require(phase=="skills");integer("front",0..2);integer("reserve",0..2);setOf("type","front","reserve") }
            "enemy_target" -> { integer("index",0..2);setOf("type","index","enemyCount") }
            "skills" -> {
                require(phase=="skills")
                val actions=action["actions"]?.jsonArray ?: throw RpcFailure("InvalidAction","Missing skill batch")
                actions.forEach {
                    val skill=it.jsonObject
                    require(skill["type"]?.jsonPrimitive?.content in setOf("servant_skill","master_skill","order_change"))
                    validateAction(phase,skill,nested=true)
                }
                if(action["delayMillis"]!=null) integer("delayMillis",0..10_000)
                setOf("type","actions","delayMillis")
            }
            "finish_skills" -> { require(phase=="skills");setOf("type") }
            "cards" -> {
                require(phase=="cards")
                val order=action["order"]?.jsonArray?.map { it.jsonPrimitive.int }
                    ?: throw RpcFailure("InvalidAction","Missing explicit card order")
                require(order.size in 3..8 && order.distinct().size==order.size && order.all { it in 1..8 }) { "Invalid explicit card order" }
                if(action["target"]!=null && action["target"]!=JsonNull) integer("target",0..2)
                setOf("type","order","target","initialHandEvidence")
            }
            else -> throw RpcFailure("InvalidAction","No supported external action")
        }
        if(action.keys.any { it !in keys }) throw RpcFailure("InvalidAction","Unknown action field")
    }
    private fun matches(receipt:ExternalReceipt,plan:ExternalPlan)=
        receipt.runId==plan.runId && receipt.battleId==plan.battleId && receipt.turnId==plan.turnId &&
            receipt.phase==plan.phase && receipt.observationId==plan.observationId && receipt.action==plan.action
    @Synchronized fun submit(plan:ExternalPlan):ExternalReceipt {
        AutomationController.identifier(plan.planId)
        if(plan.planId.length>128) throw RpcFailure("InvalidIdentifier","planId exceeds native executor bound")
        var s=session(plan.runId)
        s.receipts.firstOrNull { it.planId==plan.planId }?.let {
            if(!matches(it,plan)) throw RpcFailure("IdempotencyConflict","planId payload changed")
            return it
        }
        permit(plan.runId)
        if(s.receipts.size>=10_000) throw RpcFailure("PlanCapacity","External-plan receipt capacity exhausted")
        validateAction(plan.phase,plan.action)
        val ready=s.ready ?: throw RpcFailure("NoReadyObservation","Native executor has no stable observation")
        if(s.decisionState!="ready" || ready.battleId!=plan.battleId || ready.turnId!=plan.turnId ||
            ready.phase!=plan.phase || ready.observationId!=plan.observationId)
            throw RpcFailure("StaleObservation","External plan is stale or its observation already has an accepted action")
        val receipt=ExternalReceipt(plan.planId,plan.runId,plan.battleId,plan.turnId,plan.phase,plan.observationId,
            plan.action,"Accepted",createdAtMillis=now(),updatedAtMillis=now())
        s=s.copy(decisionState="waiting",receipts=s.receipts+receipt)
        commit(s)
        return receipt
    }
    @Synchronized fun accepted(runId:String):ExternalReceipt? = session(runId).receipts.lastOrNull { it.status=="Accepted" }
    @Synchronized fun status(runId:String,planId:String):ExternalReceipt = session(runId).receipts.firstOrNull { it.planId==planId }
        ?: throw RpcFailure("UnknownPlan","No retained external plan")
    @Synchronized fun executing(runId:String,planId:String):ExternalReceipt {
        permit(runId)
        val s=session(runId);val receipt=status(runId,planId)
        if(receipt.status!="Accepted") throw RpcFailure("NonReplayablePlan","Only an unexecuted accepted plan can dispatch")
        val ready=s.ready
        if(ready==null || ready.observationId!=receipt.observationId) {
            update(runId,planId,"Rejected","ObservationReplacedBeforeDispatch")
            throw RpcFailure("StaleObservation","Observation identity changed before dispatch")
        }
        return update(runId,planId,"Executing",null,clearReady=true)
    }
    private fun update(runId:String,planId:String,status:String,reason:String?,clearReady:Boolean=false):ExternalReceipt {
        val s=session(runId);val old=this.status(runId,planId)
        val receipt=old.copy(status=status,reason=reason,updatedAtMillis=now())
        commit(s.copy(ready=if(clearReady) null else s.ready,
            decisionState=when(status) { "Executing" -> "executing";"Unconfirmed" -> "requires_reconciliation";else -> "waiting" },
            receipts=s.receipts.map { if(it.planId==planId) receipt else it }))
        return receipt
    }
    @Synchronized fun completed(runId:String,planId:String) {
        if(status(runId,planId).status!="Executing") throw RpcFailure("InvalidDecisionState","Completion requires a durable executing receipt")
        update(runId,planId,"Completed",null,clearReady=true)
    }
    @Synchronized fun failed(runId:String,planId:String,error:String) {
        val current=status(runId,planId)
        if(current.status in setOf("Completed","Rejected","Unconfirmed")) return
        update(runId,planId,if(current.status=="Executing") "Unconfirmed" else "Rejected",error.take(512),clearReady=true)
    }
    @Synchronized fun transition(runId:String) {
        val s=session(runId)
        if(s.receipts.any { it.status in setOf("Accepted","Executing") }) throw RpcFailure("InvalidDecisionState","Unresolved action at native transition")
        commit(s.copy(decisionState="transition",ready=null))
    }
    @Synchronized fun close(runId:String,reason:String) {
        val s=journal.sessions.firstOrNull { it.runId==runId } ?: return
        val receipts=s.receipts.map {
            when(it.status) {
                "Accepted" -> it.copy(status="Rejected",reason="NativeEnded:$reason",updatedAtMillis=now())
                "Executing" -> it.copy(status="Unconfirmed",reason="NativeEnded:$reason",updatedAtMillis=now())
                else -> it
            }
        }
        commit(s.copy(decisionState=if(receipts.any { it.status=="Unconfirmed" }) "requires_reconciliation" else "ended",ready=null,receipts=receipts))
    }
}
