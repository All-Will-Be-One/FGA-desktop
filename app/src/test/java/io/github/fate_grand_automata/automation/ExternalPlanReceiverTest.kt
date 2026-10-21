package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.decision.*
import io.github.fga.automation.*
import io.github.lib_automata.Pattern
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class ExternalPlanReceiverTest {
    private class Store:ExternalJournalStore {
        var journal=ExternalJournal()
        override fun load()=journal
        override fun save(journal:ExternalJournal) {this.journal=journal}
    }
    /** Synthetic PNG header for transport tests, not a game screenshot or image-recognition fixture. */
    private fun pixels(width:Int=1280,height:Int=720)=byteArrayOf(-119,80,78,71,13,10,26,10,0,0,0,13,73,72,68,82)+
        ByteBuffer.allocate(8).putInt(width).putInt(height).array()+ByteArray(8)
    private fun pattern(width:Int=1280,height:Int=720,onClose:()->Unit={}):Pattern = Proxy.newProxyInstance(
        Pattern::class.java.classLoader,arrayOf(Pattern::class.java)
    ) { _,method,args -> when(method.name) {
        "getWidth" -> width
        "getHeight" -> height
        "save" -> { (args!![0] as java.io.OutputStream).write(pixels(width,height));Unit }
        "close" -> {onClose();Unit}
        else -> error("Unexpected Pattern operation ${method.name}")
    }} as Pattern
    @Test fun frameTokenBindsPixelsDigestGeometryAndRunRelease() {
        val dir=Files.createTempDirectory("fga-frame-test").toFile()
        try {
            val frames=ExternalFrameStore(dir,"test.automation")
            val metadata=frames.save("run",pattern())
            val token=metadata.getValue("uri").jsonPrimitive.content.substringAfterLast('/')
            assertContentEquals(pixels(),frames.read(token).readBytes())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(pixels()).joinToString(""){"%02x".format(it)},metadata.getValue("sha256").jsonPrimitive.content)
            assertEquals("normalized_1280x720",metadata.getValue("coordinateSpace").jsonPrimitive.content)
            assertFailsWith<java.io.FileNotFoundException>{frames.read("../runs-v1.json")}
            frames.release("run");assertFailsWith<java.io.FileNotFoundException>{frames.read(token)}
        } finally {dir.deleteRecursively()}
    }
    @Test fun unsupportedGeometryIsExplicitAndRestartInvalidatesTokens() {
        val dir=Files.createTempDirectory("fga-frame-test").toFile()
        try {
            val frames=ExternalFrameStore(dir,"test.automation")
            val metadata=frames.save("run",pattern(1600,720))
            assertEquals("unsupported",metadata.getValue("coordinateSpace").jsonPrimitive.content)
            val token=metadata.getValue("uri").jsonPrimitive.content.substringAfterLast('/')
            ExternalFrameStore(dir,"test.automation")
            assertFailsWith<java.io.FileNotFoundException>{frames.read(token)}
        } finally {dir.deleteRecursively()}
    }
    @Test fun captureCacheIsBoundedButRetainedFramesHaveNoAgeDeadline() {
        val dir=Files.createTempDirectory("fga-frame-test").toFile()
        try {
            val frames=ExternalFrameStore(dir,"test.automation")
            val first=frames.save("run",pattern()).getValue("uri").jsonPrimitive.content.substringAfterLast('/')
            repeat(12) {frames.save("run",pattern())}
            assertEquals(8,dir.listFiles()!!.size)
            assertFailsWith<java.io.FileNotFoundException>{frames.read(first)}
            val latest=frames.save("run",pattern()).getValue("uri").jsonPrimitive.content.substringAfterLast('/')
            assertContentEquals(pixels(),frames.read(latest).readBytes())
            frames.release("run")
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally {dir.deleteRecursively()}
    }
    @Test fun oldCaptureDeletionFailureDoesNotInvalidateNewInterfaceOrExposeOldPaths() {
        val dir=Files.createTempDirectory("fga-old-capture-test").toFile()
        try {
            val stale=object:java.io.File(dir,"old.png") {override fun delete()=false}
            stale.writeBytes(pixels())
            val directory=object:java.io.File(dir.path) {override fun listFiles()=arrayOf<java.io.File>(stale)}
            val frames=ExternalFrameStore(directory,"test.automation")
            val metadata=frames.save("run",pattern())
            val token=metadata.getValue("uri").jsonPrimitive.content.substringAfterLast('/')
            assertContentEquals(pixels(),frames.read(token).readBytes())
            assertFailsWith<java.io.FileNotFoundException>{frames.read("old.png")}
        } finally {dir.deleteRecursively()}
    }
    @Test fun semanticAdapterKeepsExplicitTargetsAndExactCardOrder() {
        val target=ExternalPlanReceiver.action(buildJsonObject {
            put("type","servant_skill");put("slot",1);put("index",2);put("target",3)
        }) as NativeDecisionAction.ServantSkill
        assertEquals(NativeDecisionAction.ServantSkill(1,2,3),target)
        val noTarget=ExternalPlanReceiver.action(buildJsonObject {
            put("type","master_skill");put("index",0);put("target",JsonNull)
        }) as NativeDecisionAction.MasterSkill
        assertNull(noTarget.target)
        val cards=ExternalPlanReceiver.action(buildJsonObject {
            put("type","cards");put("order",JsonArray(listOf(8,6,4,2,1).map(::JsonPrimitive)))
        }) as NativeDecisionAction.Cards
        assertEquals(listOf(8,6,4,2,1),cards.order)
    }
    @Test fun receiverClosesCaptureAndNeverCreatesAnActionWhileWaiting() {
        val dir=Files.createTempDirectory("fga-receiver-test").toFile()
        try {
            val store=Store();val protocol=ExternalPlanProtocol(store,{})
            protocol.open("run")
            val receiver=ExternalPlanReceiver("run",protocol,ExternalFrameStore(dir,"test.automation"),{})
            var closed=0;var checks=0
            assertFailsWith<IllegalStateException> {
                receiver.await(NativeDecisionContext(1,1,1,1,NativeDecisionPhase.Skills),{pattern(onClose={closed++})}) {
                    if(++checks>=2) error("cancelled at native safe boundary")
                }
            }
            assertEquals(1,closed)
            assertNull(protocol.accepted("run"))
            assertEquals("ready",protocol.next("run").getValue("decisionState").jsonPrimitive.content)
        } finally {dir.deleteRecursively()}
    }
    @Test fun cardsWithoutCurrentParentPublishFactsAndWaitForExternalPlan() {
        val dir=Files.createTempDirectory("fga-receiver-test").toFile()
        try {
            val protocol=ExternalPlanProtocol(Store(),{});protocol.open("run")
            val receiver=ExternalPlanReceiver("run",protocol,ExternalFrameStore(dir,"test.automation"),{})
            receiver.beforeAttack(NativeDecisionContext(1,1,1,1,NativeDecisionPhase.Skills),{pattern()})
            var checks=0
            assertFailsWith<IllegalStateException> {
                receiver.await(NativeDecisionContext(1,2,1,2,NativeDecisionPhase.Cards),{pattern()}) {
                    if(++checks>=2) error("test cancellation, not a frame-quality rejection")
                }
            }
            val ready=protocol.next("run").getValue("ready").jsonObject
            assertEquals("cards",ready.getValue("phase").jsonPrimitive.content)
            assertEquals(JsonNull,ready["beforeAttackFrame"] ?: JsonNull)
            assertEquals(JsonNull,ready["expiresAtMillis"] ?: JsonNull)
        } finally {dir.deleteRecursively()}
    }
}
