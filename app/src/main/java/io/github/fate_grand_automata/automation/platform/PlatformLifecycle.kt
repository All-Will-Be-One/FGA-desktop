package io.github.fate_grand_automata.automation.platform

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.mutableStateOf
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fate_grand_automata.automation.AutomationBridge
import io.github.fate_grand_automata.runner.ScriptRunnerService
import io.github.fga.automation.RpcFailure
import kotlinx.serialization.json.*

/** Platform lifecycle only. Caller protection stays in AutomationProvider. */
object PlatformLifecycle {
    val prepareRequested = mutableStateOf(false)
    val captureConsent = mutableStateOf(CaptureConsent())
    private val main = Handler(Looper.getMainLooper())

    fun request(context: Context, op: String, q: JsonObject): JsonObject {
        when (op) {
            "platform.status" -> return status(context)
            "platform.prepare" -> { requireIdle(); main.post {
                if (captureConsent.value.pending) return@post
                captureConsent.value = captureConsent.value.copy(closed = false)
                // Reuse the ordinary UI permission launchers, without clicking the UI.
                if (!ScriptRunnerService.serviceStarted.value) prepareRequested.value = true
            } }
            "platform.close" -> {
                requireIdle()
                main.post {
                    prepareRequested.value = false
                    captureConsent.value = captureConsent.value.close()
                    ScriptRunnerService.stopService(context)
                }
            }
            "platform.accessibility.enable", "platform.accessibility.disable" -> {
                requireIdle()
                val services = ShizukuAccessibility.setEnabled(context, op.endsWith(".enable"))
                return buildJsonObject { put("enabledServices", services); put("bindingAsynchronous", true) }
            }
            "platform.capture.permission" -> {
                val mode = q["mode"]?.jsonPrimitive?.content ?: "read"
                if (mode != "read") requireIdle()
                val raw = ShizukuAccessibility.capturePermission(context, mode)
                return buildJsonObject { put("modeRequested", mode); put("rawAppOp", raw) }
            }
            "platform.shizuku.request" -> { requireIdle(); ShizukuAccessibility.requestPermission() }
            else -> throw RpcFailure("InvalidOperation", "Unknown platform operation")
        }
        return buildJsonObject { put("accepted", true); put("operation", op) }
    }

    fun beginCapture(): Boolean {
        if (captureConsent.value.pending) return false
        captureConsent.value = captureConsent.value.begin()
        return true
    }

    fun finishCapture(granted: Boolean): Boolean {
        val allowed = captureConsent.value.acceptResult
        captureConsent.value = captureConsent.value.finish(granted)
        return allowed
    }

    private fun requireIdle() {
        if (AutomationBridge.executorBusy()) throw RpcFailure("Busy", "Stop the owned battle task and wait for quiescent before closing platform services")
    }

    private fun status(context: Context): JsonObject = buildJsonObject {
        val component = ComponentName(context, TapperService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        put("platformLifecycleVersion", 1)
        put("accessibilityEnabled", AccessibilityServiceList.contains(enabled, component))
        put("accessibilityBound", TapperService.serviceStarted.value)
        put("overlayAllowed", Settings.canDrawOverlays(context))
        put("runnerServiceStarted", ScriptRunnerService.serviceStarted.value)
        put("captureConsentTokenPresent", ScriptRunnerService.mediaProjectionToken != null)
        put("captureConsentPending", captureConsent.value.pending)
        put("executorBusy", AutomationBridge.executorBusy())
        put("shizuku", ShizukuAccessibility.status())
    }
}
