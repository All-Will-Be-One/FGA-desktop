package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.*
import io.github.lib_automata.dagger.ScriptScope
import kotlinx.serialization.json.*
import javax.inject.Inject

/** An explicit diagnostic only. No AutoBattle, input, policy or image-quality gate. */
@ScriptScope
class NativeCaptureDiagnostic @Inject constructor(
    private val screenshot: ScreenshotService,
    private val area: GameAreaManager,
    private val scale: Scale,
    private val transformer: Transformer,
    private val platform: PlatformImpl,
    private val locations: Locations,
    private val prefs: IPreferences
) {
    fun capture(store: ExternalFrameStore): JsonObject {
        val window = platform.windowRegion
        val game = area.gameArea
        val gameImage = game * (scale.screenToImage ?: 1.0)
        val battleScript = locations.battle.screenCheckRegion
        val battleImage = transformer.toImage(battleScript)
        val started = System.currentTimeMillis()
        // Only the previous explicit diagnostic is evicted; ordinary task frames are separate.
        store.release("diagnostic")
        val images = captureDiagnosticImages(screenshot, gameImage, battleImage) { pattern ->
            store.save("diagnostic", pattern)
        }
        return buildJsonObject {
            put("captureDiagnosticVersion", 1)
            put("startedAtMillis", started); put("finishedAtMillis", System.currentTimeMillis())
            put("pixelSpace", "existing-native-capture; no diagnostic resize")
            put("server", prefs.gameServer.simple)
            put("windowScreen", window.json()); put("gameAreaScreen", game.json())
            put("gameAreaImageRequested", gameImage.json())
            put("battleRegionScript", battleScript.json()); put("battleRegionImageRequested", battleImage.json())
            put("scriptArea", locations.scriptArea.json()); put("isWide", locations.isWide)
            put("screenToImage", scale.screenToImage?.let(::JsonPrimitive) ?: JsonNull)
            put("scriptToScreen", scale.scriptToScreen); put("scriptToImage", scale.scriptToImage)
            put("images", images)
        }
    }
}

internal fun Region.json() = buildJsonArray { listOf(x, y, width, height).forEach { add(it) } }

/** Clone one borrowed native frame, then derive both crops from that exact clone. */
internal fun captureDiagnosticImages(
    source: ScreenshotService, gameRegion: Region, battleRegion: Region,
    save: (Pattern) -> JsonObject
): JsonObject = source.takeScreenshot().copy().use { raw ->
    val actualGame = Region(0, 0, raw.width, raw.height).clip(gameRegion)
    raw.crop(gameRegion).use { game ->
        val actualBattle = Region(0, 0, game.width, game.height).clip(battleRegion)
        game.crop(battleRegion).use { battle ->
            buildJsonObject {
                put("native", save(raw)); put("gameArea", save(game)); put("battle", save(battle))
                put("gameAreaImageActual", actualGame.json())
                put("battleRegionImageActual", actualBattle.json())
            }
        }
    }
}
