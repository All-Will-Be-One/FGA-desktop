package io.github.fate_grand_automata.automation

import io.github.lib_automata.*
import kotlinx.serialization.json.*
import java.io.OutputStream
import kotlin.test.*

class NativeCaptureDiagnosticTest {
    private class Pixels(val rows: List<List<Int>>) : Pattern {
        var closed = false
        override val width get() = rows.first().size
        override val height get() = rows.size
        override var tag = "test"
        override fun copy() = Pixels(rows.map { it.toList() })
        override fun crop(region: Region): Pattern {
            val clipped = Region(0, 0, width, height).clip(region)
            return Pixels(rows.subList(clipped.y, clipped.bottom).map { it.subList(clipped.x, clipped.right) })
        }
        override fun close() { closed = true }
        override fun save(stream: OutputStream) = error("not needed")
        override fun resize(size: Size): Pattern = error("must not resize")
        override fun resize(target: Pattern, size: Size) = error("must not resize")
        override fun findMatches(template: Pattern, similarity: Double): Sequence<Match> = error("must not match")
        override fun threshold(value: Double): Pattern = error("must not threshold")
        override fun isWhite() = error("must not judge pixels")
        override fun isBlack() = error("must not judge pixels")
        override fun floodFill(x: Double, y: Double, maxDiff: Double, newValue: Double): Pattern = error("must not mutate")
        override fun fillText(): Pattern = error("must not mutate")
    }

    @Test fun `one borrowed frame provides unchanged pixels and both native crops`() {
        var reads = 0
        val borrowed = Pixels(List(6) { y -> List(8) { x -> y * 10 + x } })
        val source = object : ScreenshotService {
            override fun takeScreenshot(): Pattern { reads++; return borrowed }
            override fun startRecording(): AutoCloseable? = error("must not record")
            override fun close() = error("must not close existing service")
        }
        val saved = mutableListOf<Pixels>()
        val metadata = captureDiagnosticImages(source, Region(2, 1, 5, 4), Region(1, 1, 3, 2)) {
            saved += it as Pixels
            buildJsonObject { put("width", it.width); put("height", it.height) }
        }
        assertEquals(1, reads)
        assertFalse(borrowed.closed)
        assertEquals(listOf(listOf(23, 24, 25), listOf(33, 34, 35)), saved[2].rows)
        assertEquals(borrowed.rows, saved[0].rows)
        assertTrue(saved.all { it.closed })
        assertEquals(listOf(2, 1, 5, 4), metadata.getValue("gameAreaImageActual").jsonArray.map { it.jsonPrimitive.int })
    }

    @Test fun `save failures still release owned image clones`() {
        val borrowed = Pixels(List(3) { List(4) { 0 } })
        val source = object : ScreenshotService {
            override fun takeScreenshot(): Pattern = borrowed
            override fun startRecording(): AutoCloseable? = null
            override fun close() {}
        }
        var clone: Pixels? = null
        assertFailsWith<IllegalStateException> {
            captureDiagnosticImages(source, Region(0, 0, 4, 3), Region(1, 1, 2, 1)) {
                clone = it as Pixels
                error("storage failed")
            }
        }
        assertTrue(assertNotNull(clone).closed)
        assertFalse(borrowed.closed)
    }
}
