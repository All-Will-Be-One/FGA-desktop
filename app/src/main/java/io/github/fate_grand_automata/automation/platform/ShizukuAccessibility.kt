package io.github.fate_grand_automata.automation.platform

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.fate_grand_automata.accessibility.TapperService
import io.github.fga.automation.RpcFailure
import kotlinx.serialization.json.*
import rikka.shizuku.Shizuku

object ShizukuAccessibility {
    fun status(): JsonObject = buildJsonObject {
        val available = Shizuku.pingBinder()
        val granted = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        put("available", available)
        put("permissionGranted", granted)
        put("backendUid", if (granted) JsonPrimitive(Shizuku.getUid()) else JsonNull)
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) throw RpcFailure("ShizukuUnavailable", "Start the already installed Shizuku service first")
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
        if (Shizuku.shouldShowRequestPermissionRationale())
            throw RpcFailure("RequiresUserConsent", "Shizuku permission was denied; review it in Shizuku")
        Shizuku.requestPermission(7737)
    }

    fun setEnabled(context: Context, enabled: Boolean): String {
        requireShell()
        val user = (context.applicationInfo.uid / 100000).toString()
        val component = ComponentName(context, TapperService::class.java).flattenToString()
        fun setting(verb: String, key: String, value: String? = null): String {
            val args = mutableListOf("--user", user, verb, "secure", key)
            if (value != null) args.add(value)
            return ShizukuShell.settings(args)
        }
        val before = setting("get", "enabled_accessibility_services").let { if (it == "null") null else it }
        val after = AccessibilityServiceList.update(before, component, enabled)
        if (after != before) setting("put", "enabled_accessibility_services", after ?: "")
        if (enabled) setting("put", "accessibility_enabled", "1")
        else if (after.isNullOrBlank()) setting("put", "accessibility_enabled", "0")
        return setting("get", "enabled_accessibility_services")
    }

    fun capturePermission(context: Context, mode: String): String {
        CapturePermissionMode.validate(mode)
        requireShell()
        val user = (context.applicationInfo.uid / 100000).toString()
        if (mode != "read") ShizukuShell.appops(listOf("set", "--user", user, context.packageName, "PROJECT_MEDIA", mode))
        return ShizukuShell.appops(listOf("get", "--user", user, context.packageName, "PROJECT_MEDIA"))
    }

    private fun requireShell() {
        if (!Shizuku.pingBinder()) throw RpcFailure("ShizukuUnavailable", "Shizuku is not running")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw RpcFailure("RequiresUserConsent", "Explicit Shizuku client permission is required")
        if (Shizuku.getUid() != 2000) throw RpcFailure("UnsupportedBackend", "Only the ADB/shell Shizuku backend is supported")
    }
}
