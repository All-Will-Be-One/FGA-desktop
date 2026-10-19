package io.github.fga.desktop

import io.github.fate_grand_automata.scripts.FgoGameAreaManager
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.lib_automata.Location
import io.github.lib_automata.Size
import kotlinx.serialization.json.*

/** Desktop entry point. Every parsed command and geometry result comes from upstream. */
fun main(args: Array<String>) {
    try {
        if (args.firstOrNull()=="control") { require(args.size==3); control(args[1],args[2]); return }
        val result = when (args.firstOrNull()) {
            "capabilities" -> buildJsonObject {
                put("engine", "fga-desktop")
                put("parse", true)
                put("geometry", true)
                put("screenshot", true)
                put("battle", false)
                put("controlled_routine", true)
                put("routine_state", "FGA.RoutineBattle")
                put("observation_provider", "external-readonly")
                put("reason", "Standalone scripted battle is unavailable; routine execution requires the managed Smart policy/observation worker")
            }
            "screenshot" -> {
                require(args.size == 3) { "screenshot requires SERIAL OUTPUT.png" }
                val output = java.nio.file.Path.of(args[2])
                java.nio.file.Files.write(output, AdbTransport(args[1]).screenshot())
                buildJsonObject { put("screenshot", output.toAbsolutePath().toString()) }
            }
            "parse" -> {
                require(args.size == 2) { "parse requires one FGA skill command string" }
                val command = AutoSkillCommand.parse(args[1])
                buildJsonObject {
                    put("source", "FGA.AutoSkillCommand")
                    putJsonArray("waves") {
                        command.stages.forEach { wave ->
                            addJsonArray {
                                wave.forEach { turn ->
                                    addJsonArray { turn.forEach { add(it.javaClass.simpleName) } }
                                }
                            }
                        }
                    }
                }
            }
            "geometry" -> {
                require(args.size == 3) { "geometry requires width height" }
                val area = FgoGameAreaManager(Size(args[1].toInt(), args[2].toInt())) { Location(0, 0) }.gameArea
                buildJsonObject {
                    put("source", "FGA.FgoGameAreaManager")
                    put("x", area.x); put("y", area.y)
                    put("width", area.width); put("height", area.height)
                }
            }
            else -> error("Commands: capabilities | parse COMMAND | geometry WIDTH HEIGHT | screenshot SERIAL OUTPUT.png")
        }
        println(result)
    } catch (error: Exception) {
        println(buildJsonObject { put("error", error.message ?: error.javaClass.simpleName) })
        kotlin.system.exitProcess(1)
    }
}
