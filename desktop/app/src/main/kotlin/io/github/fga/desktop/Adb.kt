package io.github.fga.desktop

import io.github.lib_automata.GestureService
import io.github.lib_automata.Location
import io.github.lib_automata.Pattern
import io.github.lib_automata.ScreenshotService
import java.nio.file.Files
import java.util.concurrent.TimeUnit

interface ControlTransport : AutoCloseable {
    fun execute(vararg args: String): ByteArray
    fun screenshot(): ByteArray
}

/** Host-side transport. The serial is mandatory and passed as an argument, never shell text. */
class AdbTransport(val serial: String, private val executable: String = "adb") : ControlTransport {
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    @Volatile private var closed = false
    override fun close() {
        closed = true
        active.toList().forEach { process ->
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(20, TimeUnit.SECONDS)) { "ADB cleanup incomplete" }
        }
    }
    init {
        require(serial.matches(Regex("[A-Za-z0-9_.:-]+")) && !serial.startsWith("-"))
    }

    override fun execute(vararg args: String): ByteArray {
        check(!closed) { "ADB transport closed" }
        val output = Files.createTempFile("fga-adb-", ".out")
        val error = Files.createTempFile("fga-adb-", ".err")
        val process = try {
            ProcessBuilder(listOf(executable, "-s", serial) + args)
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start()
        } catch (failure: Exception) {
            Files.deleteIfExists(output); Files.deleteIfExists(error)
            throw failure
        }
        active.add(process)
        try {
            check(!closed) { "ADB transport closed during launch" }
            check(process.waitFor(15, TimeUnit.SECONDS)) { "ADB timeout" }
            check(process.exitValue() == 0) { "ADB failed: ${Files.readString(error).take(2048)}" }
            return Files.readAllBytes(output)
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor() }
            active.remove(process)
            Files.deleteIfExists(output); Files.deleteIfExists(error)
        }
    }

    override fun screenshot(): ByteArray = execute("exec-out", "screencap", "-p").also {
        require(it.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) {
            "ADB did not return a PNG"
        }
    }
}

/** Coordinates reaching this interface have already been transformed by libautomata. */
class AdbGestures(private val adb: ControlTransport) : GestureService {
    override fun click(location: Location, times: Int) {
        require(times in 1..100 && location.x >= 0 && location.y >= 0)
        repeat(times) { adb.execute("shell", "input", "tap", location.x.toString(), location.y.toString()) }
    }
    override fun swipe(start: Location, end: Location) {
        require(start.x >= 0 && start.y >= 0 && end.x >= 0 && end.y >= 0)
        adb.execute("shell", "input", "swipe", start.x.toString(), start.y.toString(),
            end.x.toString(), end.y.toString(), "300")
    }
    override fun close() = Unit
}

/** Connect the desktop Pattern implementation here; retain upstream ScreenshotManager. */
class AdbScreenshots(private val adb: AdbTransport, private val decode: (ByteArray) -> Pattern) : ScreenshotService {
    override fun takeScreenshot(): Pattern = decode(adb.screenshot())
    override fun startRecording(): AutoCloseable? = null
    override fun close() = Unit
}
