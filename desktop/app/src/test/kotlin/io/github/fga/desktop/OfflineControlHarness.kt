package io.github.fga.desktop

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.serialization.json.*

/** Test entry only: cannot construct AdbTransport. Used by cross-language tests. */
object OfflineControlHarness {
    @JvmStatic fun main(args:Array<String>) {
        val inputs=mutableListOf<List<String>>()
        var fault="";var dispatching=false;var inputStart=0
        val region=args.last()
        val pkg=mapOf("JP" to "com.aniplex.fategrandorder","NA" to "com.aniplex.fategrandorder.en","CN" to "com.bilibili.fatego","TW" to "com.xiaomeng.fategrandorder").getValue(region)
        val png=ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB),"png",out);out.toByteArray() }
        val transport=object:ControlTransport {
            override fun execute(vararg args:String):ByteArray {
                if(dispatching && args.take(3)==listOf("shell","dumpsys","activity") &&
                    (fault=="before_guard" || (fault=="after_guard" && inputs.size>inputStart))) error("injected guard failure")
                if(args.take(3)==listOf("shell","dumpsys","activity"))return "mResumedActivity: ActivityRecord{ x $pkg/.Main }".toByteArray()
                require(args.take(2)==listOf("shell","input"));inputs.add(args.toList())
                if(dispatching && fault=="transport") error("injected transport failure")
                return byteArrayOf()
            }
            override fun screenshot()=png
            override fun close()=Unit
        }
        var now=1000L
        val session=ControlSession("OFFLINE",region,transport,Path.of("."),{now},{if(dispatching && fault=="wait" && inputs.size>inputStart) error("injected wait failure");now+=it})
        try { generateSequence(::readLine).forEach { line ->
            val q=Json.parseToJsonElement(line).jsonObject
            dispatching=q["method"]?.jsonPrimitive?.content=="cards";inputStart=inputs.size
            val result=try {
                val value=if(q["method"]?.jsonPrimitive?.content=="test_inputs") buildJsonObject {
                    putJsonArray("inputs") { inputs.forEach { row -> add(buildJsonArray { row.forEach { add(it) } }) } }
                } else if(q["method"]?.jsonPrimitive?.content=="test_fault") {
                    fault=q.getValue("fault").jsonPrimitive.content;buildJsonObject { put("configured",true) }
                } else session.request(q)
                buildJsonObject { put("id",q.getValue("id"));put("result",value) }
            } catch(error:Exception) { controlErrorReply(q.getValue("id"),error) }
            finally { dispatching=false }
            println(result)
        } } finally {session.close()}
    }
}
