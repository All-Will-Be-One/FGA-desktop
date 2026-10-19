package io.github.fate_grand_automata.scripts.modules

/** Routine state only; inputs require explicit semantic evidence from the observer.
 * Unchanged pages wait passively. A new frame id alone never authorizes a retry.
 */
class RoutineBattle(private val operation:String, private val target:Int?,
    private val untilApEmpty:Boolean, private val clock:()->Long={System.nanoTime()/1_000_000},
    private val transitionTimeout:Long=30_000) {
    data class Decision(val kind:String,val reason:String?=null,val action:String?=null,
        val wins:Int=0,val turn:Int=0,val newVictory:Boolean=false)
    var wins=0; private set
    var turn=0; private set
    private var started=false
    private var inBattle=false
    private var settling=false
    private var materialPageSeen=false
    private var awaiting:String?=null
    private var inputPage:String?=null
    private var waitingSince=0L
    private var declining=false
    private var terminal:Decision?=null
    private val resultPages=mutableSetOf<String>()
    init {
        require(operation in setOf("battle","farm"))
        require(if(untilApEmpty) operation=="farm" && target==null else target!=null && target in 1..100)
        require(operation!="battle" || target==1)
    }
    private fun stop(reason:String,success:Boolean=false):Decision {
        val d=Decision(if(success) "complete" else "handoff",reason,wins=wins,turn=turn)
        terminal=d;return d
    }
    private fun waitPage():Decision = if(clock()-waitingSince>=transitionTimeout) stop("transition_timeout")
        else Decision("observe",wins=wins,turn=turn)
    private fun input(action:String,page:String):Decision {
        if(inputPage==page && awaiting!=null)return waitPage()
        awaiting=action;inputPage=page;waitingSince=clock()
        return Decision("action",action=action,wins=wins,turn=turn)
    }
    fun advance(scene:String,page:String=scene):Decision {
        terminal?.let{return it}
        if(scene=="ap_empty")return stop("ap_empty",untilApEmpty)
        if(scene=="defeated")return stop(if(inBattle) "lost" else "unsupported_start_screen")
        if(declining) {
            if(scene=="quest" || scene=="support")return stop("target_reached",true)
            return waitPage()
        }
        if(scene=="unknown" || scene=="loading") {
            if(awaiting!=null)return waitPage()
            return Decision(if(settling) "post_boundary" else if(!started) "pre_boundary" else "observe",wins=wins,turn=turn)
        }
        if(scene=="boundary") {
            awaiting=null;inputPage=null
            return Decision(if(settling) "post_boundary" else "pre_boundary",wins=wins,turn=turn)
        }
        if(scene=="repeat_verified") {
            if(inBattle)return stop("unexpected_repeat_in_battle")
            if(target!=null && wins>=target) {
                declining=true;return input("repeat_decline",page)
            }
            started=true;settling=false;turn=0;resultPages.clear()
            return input("repeat_continue",page)
        }
        if(scene=="repeat" || scene=="dialog")return stop("unverified_dialog")
        if(scene=="result" || scene.startsWith("result_")) {
            if(scene=="result")materialPageSeen=true
            if(!inBattle && !settling)return stop("unsupported_start_screen")
            if(inBattle) {
                wins++;inBattle=false;settling=true;awaiting=null;inputPage=null;resultPages.clear()
                return Decision("post_boundary",wins=wins,turn=turn,newVictory=true)
            }
            // A page kind, not arbitrary pixel changes, permits a next result input.
            if(page in resultPages)return waitPage()
            resultPages.add(page);return input("result_next",page)
        }
        if(scene=="friend_request") {
            if(!settling)return stop("unexpected_friend_request")
            return input("friend_decline",page)
        }
        if(scene=="quest") {
            if(settling && target!=null && wins>=target)return stop("target_reached",true)
            if(operation!="farm")return stop("unsupported_start_screen")
            started=true;turn=0;return input("quest_first",page)
        }
        if(scene=="turn") {
            if(settling && target!=null && wins>=target)return stop("unexpected_battle_after_target")
            if(settling)turn=0
            awaiting=null;inputPage=null;settling=false;started=true;inBattle=true;turn++
            return Decision("turn",wins=wins,turn=turn)
        }
        if(inBattle)return stop("battle_screen_requires_agent")
        return when(scene) {
            "support", "support_empty" -> if(started) {turn=0;input("support_select",page)} else Decision("pre_boundary",wins=wins)
            "formation" -> if(started) {turn=0;input("formation_start",page)} else Decision("pre_boundary",wins=wins)
            "formation_confirm" -> if(awaiting in setOf("formation_start","formation_confirm")) input("formation_confirm",page) else stop("unexpected_formation_confirmation")
            "boost_selection" -> if(started) input("boost_skip",page) else stop("unsupported_start_screen")
            else -> stop("unsupported_start_screen")
        }
    }
    fun finishBoundary():Decision {
        terminal?.let{return it}
        if(operation=="battle" && settling && materialPageSeen && target!=null && wins>=target)return stop("target_reached",true)
        return Decision("observe",wins=wins,turn=turn)
    }
    fun boundaryLanding(scene:String):Decision {
        if(!scene.startsWith("result_") && scene !in setOf("turn","support","support_empty","formation","boost_selection","quest","repeat_verified","result","ap_empty","friend_request"))return stop("boundary_unconfirmed")
        started=true;return advance(scene)
    }
}
