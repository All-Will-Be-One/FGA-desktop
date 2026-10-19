package io.github.fga.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.serialization.json.*
import kotlin.test.*

class ControlSessionTest {
    private class Transport : ControlTransport {
        var closed=false
        var foreground="com.aniplex.fategrandorder"
        var width=1280
        var height=720
        var failInput=0
        var duringCapture:()->Unit={}
        val inputs=mutableListOf<List<String>>()
        override fun execute(vararg args:String):ByteArray {
            check(!closed)
            if(args.take(3)==listOf("shell","dumpsys","activity"))return "mResumedActivity: ActivityRecord{ x $foreground/.Main }".toByteArray()
            require(args.take(3)==listOf("shell","input","tap"))
            inputs.add(args.toList())
            if(inputs.size==failInput)error("uncertain transport")
            return byteArrayOf()
        }
        override fun screenshot():ByteArray = ByteArrayOutputStream().use { out ->
            duringCapture()
            val image=BufferedImage(width,height,BufferedImage.TYPE_INT_RGB)
            // Deterministic center marker proves normalized pixels and input use the same crop.
            image.setRGB(width/2,height/2,0xFFFFFF)
            ImageIO.write(image,"png",out);out.toByteArray()
        }
        override fun close() { closed=true }
    }
    private fun init(resources:String="{\"apples\":false,\"sq\":false,\"command_spells\":false,\"boost\":false,\"ap_wait\":false}") =
        Json.parseToJsonElement("""{"method":"init","operation":"battle","battles":1,"until_ap_empty":false,"timeout":120,"resources":$resources}""").jsonObject
    private fun q(text:String)=Json.parseToJsonElement(text).jsonObject
    private fun withSession(block:(ControlSession,Transport,()->String,(Long)->Unit)->Unit) {
        val t=Transport();val dir=Files.createTempDirectory("fga-session-test")
        var now=1000L
        val session=ControlSession("FIXTURE","JP",t,dir,{ now },{ now+=it })
        try {
            session.request(init())
            block(session,t,{ session.request(q("""{"method":"observe"}"""))["frame_id"]!!.jsonPrimitive.content },{ now+=it })
        } finally {
            session.close();assertTrue(t.closed)
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
    @Test fun actualGeometryAndOneFrameInput() = withSession { s,t,observe,_ ->
        val frame=observe()
        s.request(q("""{"method":"action","frame_id":"$frame","action":"attack"}"""))
        assertEquals(listOf("shell","input","tap","1150","600"),t.inputs.single())
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"attack"}""")) }
        assertEquals(1,t.inputs.size)
    }
    @Test fun exactNPCardsUseFgaPositionsWithoutExtraTap() = withSession { s,t,observe,advance ->
        val first=observe();advance(150);val second=observe()
        val result=s.request(q("""{"method":"cards","deadline_ms":30000,"frames":["$first","$second"],"plan":[6,2,1],"available":[true,true,true,true,true,true,true,true],"selecting":true,"partial":false}"""))
        assertEquals(3,result["attempted"]!!.jsonPrimitive.int)
        assertEquals(listOf(listOf("500","110"),listOf("375","500"),listOf("150","500")),t.inputs.map { it.takeLast(2) })
    }
    @Test fun resourceAndUnknownActionsNeverDispatch() = withSession { s,t,observe,_ ->
        val frame=observe()
        for(action in listOf("refill","command_spell","boost","swipe","raw_tap")) {
            assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"$action"}""")) }
        }
        assertTrue(t.inputs.isEmpty())
    }
    @Test fun foregroundSizeAndStalenessFailClosed() = withSession { s,t,observe,advance ->
        val frame=observe()
        t.foreground="com.android.settings"
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"attack"}""")) }
        t.foreground="com.aniplex.fategrandorder";t.width=1200
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"attack"}""")) }
        t.width=1280;advance(30001)
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"attack"}""")) }
        assertTrue(t.inputs.isEmpty())
    }
    @Test fun uncertainSingleInputConsumesFrame() = withSession { s,t,observe,_ ->
        val frame=observe();t.failInput=1
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"attack"}""")) }
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"attack"}""")) }
        assertEquals(1,t.inputs.size)
    }
    @Test fun initializationRequiresAllResourceProhibitions() {
        val t=Transport();val s=ControlSession("FIXTURE","JP",t)
        try {
            assertFails { s.request(init("{}")) }
            assertFails { s.request(init("{\"apples\":true,\"sq\":false,\"command_spells\":false,\"boost\":false,\"ap_wait\":false}")) }
            assertFails { s.request(q("""{"method":"observe"}""")) }
            assertTrue(t.inputs.isEmpty())
        } finally { s.close() }
    }
    @Test fun reviewWideFramesUseCenteredCropAndSixEnemyLocations() = withSession { s,t,observe,_ ->
        t.width=1600
        val frame=observe()
        s.request(q("""{"method":"action","frame_id":"$frame","action":"enemy_target","index":5,"count":6}"""))
        assertEquals(listOf("698","139"),t.inputs.single().takeLast(2))
    }

    @Test fun everyResourceFlagAndMissingFlagIsRejectedBeforeInput() {
        val keys=listOf("apples","sq","command_spells","boost","ap_wait")
        for(key in keys) for(missing in listOf(false,true)) {
            val t=Transport();val s=ControlSession("FIXTURE","JP",t)
            val flags=buildJsonObject { keys.forEach { if(it!=key || !missing) put(it,it==key) } }
            try { assertFails { s.request(init(flags.toString())) };assertTrue(t.inputs.isEmpty()) }
            finally { s.close() }
        }
    }
    @Test fun boostSkipRequiresPositiveEvidenceAndUsesObservedPoint() = withSession { s,t,observe,_ ->
        val frame=observe()
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"boost_skip"}""")) }
        assertFails { s.request(q("""{"method":"action","frame_id":"$frame","action":"boost_skip","x":820,"y":650,"evidence":"bonus"}""")) }
        assertTrue(t.inputs.isEmpty())
        s.request(q("""{"method":"action","frame_id":"$frame","action":"boost_skip","x":820,"y":650,"evidence":"boost_selection"}"""))
        assertEquals(listOf("820","650"),t.inputs.single().takeLast(2))
    }

    @Test fun centeredPixelsAndInputRoundTripForWideAndLetterbox() {
        for(size in listOf(1600 to 720,1800 to 720,1280 to 800)) withSession { s,t,_,_ ->
            t.width=size.first;t.height=size.second
            val f=s.request(q("""{"method":"observe"}"""))
            val rect=f["normalized_source_rect"]!!.jsonArray.map { it.jsonPrimitive.int }
            assertEquals(listOf((t.width-1280)/2,(t.height-720)/2,1280,720),rect)
            val bitmap=ImageIO.read(java.io.File(f["screenshot"]!!.jsonPrimitive.content))
            assertEquals(0xFFFFFF,bitmap.getRGB(640,360) and 0xFFFFFF)
            val id=f["frame_id"]!!.jsonPrimitive.content
            s.request(q("""{"method":"action","frame_id":"$id","action":"boost_skip","x":640,"y":360,"evidence":"boost_selection"}"""))
            assertEquals(listOf((t.width/2).toString(),(t.height/2).toString()),t.inputs.single().takeLast(2))
        }
    }

    @Test fun sharedProductionErrorEnvelopeKeepsZeroAndLatestUncertainty() {
        val cause=IllegalStateException("injected")
        for((count,uncertain) in listOf(0 to false,1 to false,1 to true,3 to false)) {
            val failure=io.github.fate_grand_automata.scripts.modules.CardDispatchInterrupted(count,uncertain,cause)
            val reply=controlErrorReply(JsonPrimitive(7),failure)
            assertEquals(7,reply["id"]!!.jsonPrimitive.int)
            assertEquals(count,reply["attempted"]!!.jsonPrimitive.int)
            assertEquals(uncertain,reply["latest_attempt_uncertain"]!!.jsonPrimitive.boolean)
            assertEquals(count>0,reply["delivery_unknown"]!!.jsonPrimitive.boolean)
            assertEquals("unverified",reply["game_effect"]!!.jsonPrimitive.content)
        }
    }

    @Test fun entryWaitHasWireEvidenceAndOnlyOneInput() = withSession { s,t,observe,advance ->
        val first=s.request(q("""{"method":"boundary_landing","scene":"formation"}"""))
        assertEquals("formation_start",first["action"]!!.jsonPrimitive.content)
        val frame=observe()
        s.request(q("""{"method":"action","frame_id":"$frame","action":"formation_start"}"""))
        advance(31000)
        val wait=s.request(q("""{"method":"advance","scene":"loading"}"""))
        assertEquals("observe",wait["kind"]!!.jsonPrimitive.content)
        assertEquals(90000L,wait["wait_budget_ms"]!!.jsonPrimitive.long)
        assertEquals(31000L,wait["wait_elapsed_ms"]!!.jsonPrimitive.long)
        assertEquals("formation_start",wait["awaiting_action"]!!.jsonPrimitive.content)
        advance(1000)
        assertEquals("turn",s.request(q("""{"method":"advance","scene":"turn"}"""))["kind"]!!.jsonPrimitive.content)
        assertEquals(1,t.inputs.size)
    }
    @Test fun jobDeadlineAndCloseCanEndEntryWaitEarly() {
        for(close in listOf(false,true)) {
            val t=Transport();var now=1000L
            val s=ControlSession("FIXTURE","JP",t,clock={now})
            try {
                s.request(q(init().toString().replace("\"timeout\":120","\"timeout\":20")))
                s.request(q("""{"method":"boundary_landing","scene":"formation"}"""))
                if(close)s.close() else now+=20000
                assertFails { s.request(q("""{"method":"advance","scene":"loading"}""")) }
                assertTrue(t.inputs.isEmpty())
            } finally { s.close() }
        }
    }
    @Test fun captureCrossingEntryCutoffCannotAcceptReadyOrAuthorizeBoost() {
        for(scene in listOf("turn","boost_selection","formation_confirm")) withSession { s,t,observe,advance ->
            s.request(q("""{"method":"boundary_landing","scene":"formation"}"""))
            advance(89999)
            t.duringCapture={advance(2)};observe();t.duringCapture={}
            val d=s.request(q("""{"method":"advance","scene":"$scene"}"""))
            assertEquals("transition_timeout",d["reason"]!!.jsonPrimitive.content)
            assertFalse("action" in d);assertEquals(0,d["turn"]!!.jsonPrimitive.int)
            assertEquals(90001L,d["wait_elapsed_ms"]!!.jsonPrimitive.long)
            assertEquals(1000L,d["entry_started_at_ms"]!!.jsonPrimitive.long)
            assertEquals(91000L,d["entry_deadline_ms"]!!.jsonPrimitive.long)
            assertEquals(listOf("formation_start"),d["entry_issued_actions"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue(t.inputs.isEmpty())
        }
    }
    @Test fun inputGuardCrossingEntryDeadlinePreventsAuthorizedBoostDispatch() = withSession { s,t,observe,advance ->
        s.request(q("""{"method":"boundary_landing","scene":"formation"}"""))
        val initial=observe()
        s.request(q("""{"method":"action","frame_id":"$initial","action":"formation_start"}"""))
        advance(89999)
        val d=s.request(q("""{"method":"advance","scene":"boost_selection"}"""))
        assertEquals("boost_skip",d["action"]!!.jsonPrimitive.content)
        val captured=observe()
        // Fresh frame, but a blocking foreground/geometry guard crosses entry expiry.
        t.duringCapture={advance(2)}
        val failure=assertFails { s.request(q("""{"method":"action","frame_id":"$captured","action":"boost_skip","x":820,"y":650,"evidence":"boost_selection"}""")) }
        assertEquals("transition_timeout",failure.message)
        assertEquals(1,t.inputs.size)
        val expired=s.request(q("""{"method":"advance","scene":"turn"}"""))
        assertEquals("transition_timeout",expired["reason"]!!.jsonPrimitive.content)
        assertEquals(90001L,expired["wait_elapsed_ms"]!!.jsonPrimitive.long)
        assertEquals(listOf("formation_start","boost_skip"),expired["entry_issued_actions"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
}
