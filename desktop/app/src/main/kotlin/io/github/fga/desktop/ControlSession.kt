package io.github.fga.desktop

import io.github.lib_automata.*
import io.github.fate_grand_automata.scripts.modules.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.UUID
import java.lang.reflect.Proxy
import javax.imageio.ImageIO

/** One job-scoped FGA execution process. JSONL is private worker transport only. */
class ControlSession(private val serial: String, private val region: String,
                     private val adb: ControlTransport = AdbTransport(serial),
                     private val directory: Path = Path.of("."),
                     private val clock: () -> Long = { System.nanoTime()/1_000_000 },
                     private val pause: (Long) -> Unit = { Thread.sleep(it) }) : AutoCloseable {
    private val exit = ExitManager()
    private val frames = linkedMapOf<String, Frame>()
    private var geometry: ControlGeometry? = null
    private var initialized = false
    private var routine: RoutineBattle? = null
    private var deadline = 0L
    private var expectedSize: Size? = null
    private var used = mutableSetOf<String>()
    private val packageName = mapOf("JP" to "com.aniplex.fategrandorder", "NA" to "com.aniplex.fategrandorder.en",
        "CN" to "com.bilibili.fatego", "TW" to "com.xiaomeng.fategrandorder").getValue(region)
    data class Frame(val id: String, val time: Long, val path: String, val raw: String)
    private fun now() = clock()
    private fun checkTime() { exit.checkExitRequested(); check(initialized && now()<deadline) { "Job deadline or initialization failure" } }
    private fun foreground() {
        val text = adb.execute("shell","dumpsys","activity","activities").toString(Charsets.UTF_8)
        val match = listOf("topResumedActivity","mResumedActivity","ResumedActivity").firstNotNullOfOrNull { label ->
            Regex("\\b"+label+"\\s*[:=][^\\n]*?\\s([A-Za-z0-9_.]+)/").find(text)?.groupValues?.get(1)
        }
        check(match==packageName) { "FGO foreground changed or unknown" }
    }
    private fun image() = ImageIO.read(ByteArrayInputStream(adb.screenshot())) ?: error("PNG decode failed")
    private fun guard() {
        checkTime(); foreground()
        val current=image()
        check(Size(current.width,current.height)==expectedSize) { "Display geometry changed" }
        foreground(); checkTime()
    }
    private fun frame(id: String): Frame {
        val f=frames[id] ?: error("Unknown frame")
        check(id !in used && now()-f.time in 0..30_000) { "Stale or consumed frame" }
        return f
    }
    private fun click(point: Location) {
        val g=requireNotNull(geometry)
        val screen=g.transform.toScreen(point)
        val size=requireNotNull(expectedSize)
        require(screen.x in 0 until size.width && screen.y in 0 until size.height)
        RealClicker(AdbGestures(adb),exit,g.transform)(point,1)
    }
    private fun observe(): JsonObject {
        checkTime();foreground()
        val started=java.time.Instant.now().toString()
        val raw=image();val captured=now()
        val completed=java.time.Instant.now().toString()
        foreground();checkTime()
        val size=Size(raw.width,raw.height)
        if(expectedSize==null) { expectedSize=size;geometry=ControlGeometry(size,region) }
        check(size==expectedSize) { "Display geometry changed" }
        val area=requireNotNull(geometry).area.gameArea
        val cropWidth=minOf(area.width,(area.height*16.0/9).toInt())
        val cropX=area.x+(area.width-cropWidth)/2
        val id=UUID.randomUUID().toString()
        val rawPath=directory.resolve("fga-raw-$id.png").toAbsolutePath()
        val path=directory.resolve("fga-frame-$id.png").toAbsolutePath()
        ImageIO.write(raw,"png",rawPath.toFile())
        val normalized=java.awt.image.BufferedImage(1280,720,java.awt.image.BufferedImage.TYPE_INT_RGB)
        normalized.createGraphics().let { graphics ->
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(raw,0,0,1280,720,cropX,area.y,cropX+cropWidth,area.bottom,null);graphics.dispose()
        }
        ImageIO.write(normalized,"png",path.toFile())
        frames[id]=Frame(id,captured,path.toString(),rawPath.toString())
        while(frames.size>64) { val old=frames.keys.first();frames.remove(old);used.remove(old) }
        return buildJsonObject {
            put("frame_id",id);put("screenshot",path.toString());put("raw_screenshot",rawPath.toString())
            put("width",1280);put("height",720);put("raw_width",size.width);put("raw_height",size.height)
            putJsonArray("normalized_source_rect") { add(cropX);add(area.y);add(cropWidth);add(area.height) }
            put("coordinate_space","normalized_1280x720_pixels");put("raw_coordinate_space","raw_screenshot_pixels");put("package",packageName)
            put("capture_started_at",started);put("capture_completed_at",completed)
            put("capture_time_source","host_utc_capture_call");put("source","fga-desktop")
        }
    }
    fun request(q: JsonObject): JsonObject {
        fun string(key:String)=q.getValue(key).jsonPrimitive.content
        fun integer(key:String, default:Int=0)=q[key]?.jsonPrimitive?.int ?: default
        return when(string("method")) {
            "init" -> {
                check(!initialized)
                val resources=q.getValue("resources").jsonObject
                require(resources.keys==setOf("apples","sq","command_spells","boost","ap_wait"))
                require(resources.values.all { it.jsonPrimitive.boolean==false }) { "Resource use forbidden" }
                val timeout=q.getValue("timeout").jsonPrimitive.double;require(timeout.isFinite() && timeout in 1.0..86400.0)
                val target=q["battles"]?.let { if(it is JsonNull) null else it.jsonPrimitive.int }
                routine=RoutineBattle(string("operation"),target,q.getValue("until_ap_empty").jsonPrimitive.boolean,::now)
                deadline=now()+(timeout*1000).toLong();initialized=true
                buildJsonObject { put("backend","fga-desktop");put("initialized",true) }
            }
            "clock" -> { checkTime(); buildJsonObject { put("monotonic_ms",now()) } }
            "order_regions" -> {
                checkTime();val g=requireNotNull(geometry)
                buildJsonObject { putJsonArray("centers") { (0..5).forEach { i ->
                    val p=g.transform.toImage(Region(g.locate("order_member",i),Size(1,1))).location
                    add(buildJsonArray { add(p.x-g.imageOffsetX);add(p.y) })
                } } }
            }
            "regions" -> {
                checkTime();val g=requireNotNull(geometry)
                val regions=mapOf("battle_menu" to io.github.fate_grand_automata.scripts.locations.MasterAnchoredLocations.searchRegion(g.transforms),
                    "skill_use" to g.battle.skillUseRegion,
                    "repeat" to g.locations.continueRegion,
                    "support_extra" to g.locations.resultFriendRequestRegion,
                    "result" to g.locations.resultScreenRegion,
                    "bond" to g.locations.resultBondRegion,
                    "master_exp" to g.locations.resultMasterExpRegion,
                    "master_lvl_up" to g.locations.resultMasterLvlUpRegion,
                    "mat_rewards" to g.locations.resultMatRewardsRegion,
                    "support_refresh" to g.support.refreshRegion,
                    "cancel" to Region(0,900,g.transforms.scriptArea.width/2,540),
                    "start_quest" to g.locations.ordealCallConfirmPodUseRegion)
                buildJsonObject { regions.forEach { (name,rect) ->
                    val image=g.transform.toImage(rect)
                    putJsonArray(name) { add(image.x-g.imageOffsetX);add(image.y);add(image.width);add(image.height) }
                } }
            }
            "advance", "boundary_landing", "finish_boundary" -> {
                checkTime()
                val engine=requireNotNull(routine)
                val d=when(string("method")) { "advance" -> engine.advance(string("scene"),q["page"]?.jsonPrimitive?.content ?: string("scene")); "finish_boundary" -> engine.finishBoundary(); else -> engine.boundaryLanding(string("scene")) }
                buildJsonObject {
                    put("kind",d.kind);d.reason?.let { put("reason",it) };d.action?.let { put("action",it) }
                    put("completed_battles",d.wins);put("turn",d.turn);put("new_victory",d.newVictory)
                    d.awaitingAction?.let { put("awaiting_action",it) };d.scene?.let { put("scene",it) }
                    d.waitElapsedMs?.let { put("wait_elapsed_ms",it) };d.waitBudgetMs?.let { put("wait_budget_ms",it) }
                }
            }
            "observe" -> observe()
            "action" -> {
                checkTime()
                val f=frame(string("frame_id"));val action=string("action");val g=requireNotNull(geometry)
                val anchor=q["anchor"]?.jsonArray?.let { require(it.size==2);g.fromNormalized(it[0].jsonPrimitive.int,it[1].jsonPrimitive.int) }
                if(action=="support_scroll") {
                    guard();check(now()-f.time<=30_000);used.add(f.id)
                    try { RealSwiper(AdbGestures(adb),g.platform,g.transform)(g.support.listSwipeStart,g.support.listSwipeEnd) }
                    catch(failure:Exception) { throw CardDeliveryUnknown(1,failure) }
                    return buildJsonObject { put("status","input_delivered");put("game_effect","unverified") }
                }
                val point=if(action in setOf("boundary_tap","repeat_continue","repeat_decline","support_match","formation_confirm","boost_skip")) {
                    require(string("evidence") in setOf("story","skip_confirmation","bonus","skill_failure","repeat_disable_resource","support_refresh_confirm","repeat_verified","support_template","formation_confirm","boost_selection"))
                    if(action=="boost_skip") require(string("evidence")=="boost_selection")
                    val x=integer("x");val y=integer("y");require(x in 0..1279 && y in 0..719)
                    g.fromNormalized(x,y)
                } else g.locate(action,integer("index"),integer("slot"),integer("count"),anchor)
                guard();check(now()-f.time<=30_000) { "Stale frame after validation" }
                used.add(f.id)
                try { click(point) } catch(failure: Exception) { throw CardDeliveryUnknown(1,failure) }
                buildJsonObject { put("status","input_delivered");put("game_effect","unverified") }
            }
            "cards" -> {
                checkTime()
                val g=requireNotNull(geometry)
                val plan=q.getValue("plan").jsonArray.map { g.card(it.jsonPrimitive.int) }
                val ids=q.getValue("frames").jsonArray.map { it.jsonPrimitive.content }
                val saved=ids.map { frame(it) }
                val available=q.getValue("available").jsonArray
                require(available.size==8)
                val evidence=saved.map { f -> CardInputEvidence(f.id,f.time,"fgopy-readonly-ast",true,false,
                    (1..8).associate { code -> g.card(code) to if(available[code-1] is JsonNull) null else available[code-1].jsonPrimitive.boolean }) }
                require(q.getValue("selecting").jsonPrimitive.boolean && !q.getValue("partial").jsonPrimitive.boolean)
                val api=Proxy.newProxyInstance(AutomataApi::class.java.classLoader,arrayOf(AutomataApi::class.java)) { _,method,args ->
                    check(method.name=="click" && args[0] is Location && args[1]==1) { "Unsupported controlled API call" }
                    click(args[0] as Location);null
                } as AutomataApi
                val transactionDeadline=q.getValue("deadline_ms").jsonPrimitive.long
                require(transactionDeadline<=now()+30_000) { "Invalid card deadline" }
                val runner=ControlledCards(api,g.attack,::now,{ guard();check(saved.all { now()-it.time<=30_000 }) },
                    { attempted ->
                        val end=now()+(if(attempted<3) 300 else 2300)
                        while(now()<end) { checkTime();check(now()<transactionDeadline) { "Card transaction deadline" };pause(minOf(50,end-now(),transactionDeadline-now())) }
                    })
                // Consume before dispatch, including cancellation and uncertain delivery.
                ids.forEach { used.add(it) }
                val result=runner.dispatch(plan,evidence,transactionDeadline)
                buildJsonObject { put("attempted",result.attempted);put("game_effect",result.gameEffect) }
            }
            else -> error("Unsupported control method")
        }
    }
    override fun close() { exit.exit();adb.close() }
}

/** Shared production wire envelope. Transport return is never game acceptance. */
fun controlErrorReply(id: JsonElement, failure: Exception): JsonObject = buildJsonObject {
    put("id",id);put("error",failure.message ?: failure.javaClass.simpleName)
    put("delivery_unknown",failure is CardDispatchInterrupted && failure.attempted>0)
    if(failure is CardDispatchInterrupted) {
        put("attempted",failure.attempted)
        put("latest_attempt_uncertain",failure.latestAttemptUncertain)
        put("game_effect","unverified")
    }
}

fun control(serial: String, region: String) {
    val session=ControlSession(serial,region)
    val hook=Thread { session.close() }
    Runtime.getRuntime().addShutdownHook(hook)
    try {
        generateSequence(::readLine).forEach { line ->
            var id: JsonElement=JsonNull
            var method:String?=null
            val reply=try {
                val q=Json.parseToJsonElement(line).jsonObject;id=q.getValue("id");method=q["method"]?.jsonPrimitive?.content
                if(q["method"]?.jsonPrimitive?.content=="close") return
                buildJsonObject { put("id",id);put("result",session.request(q)) }
            } catch(failure: Exception) {
                controlErrorReply(id,failure)
            }
            println(reply);System.out.flush()
        }
    } finally { session.close();Runtime.getRuntime().removeShutdownHook(hook) }
}
