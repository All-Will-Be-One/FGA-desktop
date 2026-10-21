package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.decision.*
import io.github.fga.automation.ExternalPlanProtocol
import io.github.lib_automata.Pattern
import kotlinx.serialization.json.*

/** Receives explicit FGOAgent actions. No skill, target, NP or card policy lives here. */
class ExternalPlanReceiver(
    private val runId:String,private val protocol:ExternalPlanProtocol,private val frames:ExternalFrameStore,
    private val requireOwner:()->Unit,
) : NativeBattleDecisionProvider {
    override val enabled=true
    private var pending:String?=null
    private fun capture(capture:()->Pattern)=capture().use { frames.save(runId,it) }
    override fun await(context:NativeDecisionContext,capture:()->Pattern,checkCancelled:()->Unit):NativeDecisionCommand {
        requireOwner();checkCancelled()
        val ready=protocol.publish(runId,contextJson(context),capture(capture))
        while(true) {
            checkCancelled();requireOwner()
            val receipt=protocol.accepted(runId)
            if(receipt!=null) {
                check(receipt.observationId==ready.observationId) { "Accepted plan belongs to another native observation" }
                pending=receipt.planId
                return NativeDecisionCommand(receipt.planId,action(receipt.action))
            }
            Thread.sleep(50)
        }
    }
    override fun executing(command:NativeDecisionCommand) {
        requireOwner();check(command.planId==pending)
        protocol.executing(runId,command.planId)
    }
    override fun completed(command:NativeDecisionCommand) { protocol.completed(runId,command.planId);pending=null }
    override fun failed(command:NativeDecisionCommand,error:String) { protocol.failed(runId,command.planId,error);pending=null }
    override fun finishedTurn(context:NativeDecisionContext) { protocol.transition(runId) }
    companion object {
        fun contextJson(context:NativeDecisionContext)=buildJsonObject {
            put("battle",context.battle);put("turn",context.turn);put("wave",context.wave);put("waveTurn",context.waveTurn)
            put("phase",if(context.phase==NativeDecisionPhase.Skills) "skills" else "cards")
            putJsonArray("cards") {
                context.cards.forEach { card -> add(buildJsonObject {
                    put("index",card.index);put("type",card.type);put("affinity",card.affinity);put("servant",card.servant)
                    put("fieldSlot",card.fieldSlot?.let(::JsonPrimitive) ?: JsonNull);put("stunned",card.stunned)
                }) }
            }
        }
        fun action(value:JsonObject):NativeDecisionAction {
            fun n(key:String)=value.getValue(key).jsonPrimitive.int
            fun target()=value["target"]?.takeUnless { it==JsonNull }?.jsonPrimitive?.int
            return when(value.getValue("type").jsonPrimitive.content) {
                "servant_skill" -> NativeDecisionAction.ServantSkill(n("slot"),n("index"),target())
                "master_skill" -> NativeDecisionAction.MasterSkill(n("index"),target())
                "order_change" -> NativeDecisionAction.OrderChange(n("front"),n("reserve"))
                "enemy_target" -> NativeDecisionAction.EnemyTarget(n("index"))
                "skills" -> NativeDecisionAction.Skills(value.getValue("actions").jsonArray.map { action(it.jsonObject) },
                    value["delayMillis"]?.jsonPrimitive?.long ?: 1500)
                "finish_skills" -> NativeDecisionAction.FinishSkills
                "cards" -> NativeDecisionAction.Cards(value.getValue("order").jsonArray.map { it.jsonPrimitive.int }, target())
                else -> error("Unsupported explicit external action")
            }
        }
    }
}
