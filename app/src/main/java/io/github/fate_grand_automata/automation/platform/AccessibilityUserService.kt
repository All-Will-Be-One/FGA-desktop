package io.github.fate_grand_automata.automation.platform

import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep
import io.github.fate_grand_automata.accessibility.TapperService
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask

/** Short-lived shell worker: this app's a11y entry and explicit PROJECT_MEDIA mode only. */
@Keep
class AccessibilityUserService @Keep constructor(context: Context) : Binder() {
    private val clientUid = context.applicationInfo.uid
    // Android UserHandle.PER_USER_RANGE; getUserId/getIdentifier are hidden SDK APIs.
    private val user = (clientUid / 100000).toString()
    private val component = ComponentName(context, TapperService::class.java).flattenToString()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }
        // Shizuku uses the reserved transaction when removing the worker.
        if (code == DESTROY && Binder.getCallingUid() in setOf(clientUid, 2000)) {
            kotlin.system.exitProcess(0)
        }
        if (code !in setOf(SET_ENABLED, CAPTURE_PERMISSION)) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        if (Binder.getCallingUid() != clientUid || Process.myUid() != 2000)
            throw SecurityException("Only the owning application and the shell backend are supported")
        if (code == CAPTURE_PERMISSION) {
            val mode = requireNotNull(data.readString())
            CapturePermissionMode.validate(mode)
            if (mode != "read") execute(listOf("/system/bin/cmd", "appops", "set", "--user", user,
                contextPackage, "PROJECT_MEDIA", mode))
            val raw = execute(listOf("/system/bin/cmd", "appops", "get", "--user", user,
                contextPackage, "PROJECT_MEDIA"))
            reply?.writeNoException()
            reply?.writeString(raw)
            return true
        }
        val enabled = data.readInt() != 0
        val before = setting("get", "enabled_accessibility_services").let { if (it == "null") null else it }
        val after = AccessibilityServiceList.update(before, component, enabled)
        if (after != before) setting("put", "enabled_accessibility_services", after ?: "")
        if (enabled) setting("put", "accessibility_enabled", "1")
        else if (after.isNullOrBlank()) setting("put", "accessibility_enabled", "0")
        reply?.writeNoException()
        reply?.writeString(setting("get", "enabled_accessibility_services"))
        return true
    }

    private fun setting(verb: String, key: String, value: String? = null): String {
        val args = mutableListOf("/system/bin/settings", "--user", user, verb, "secure", key)
        if (value != null) args.add(value)
        return execute(args)
    }

    private val contextPackage = context.packageName
    private fun execute(args: List<String>): String {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val exit = FutureTask { process.waitFor() }
        Thread(exit, "own-accessibility-settings").start()
        try {
            exit.get(5, TimeUnit.SECONDS)
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.exitValue() == 0 && !output.contains("SecurityException")) { "Platform permission command rejected: $output" }
            return output
        } finally { process.destroy(); exit.cancel(true) }
    }

    companion object {
        const val DESCRIPTOR = "io.github.fate_grand_automata.automation.platform.OwnAccessibility"
        const val SET_ENABLED = IBinder.FIRST_CALL_TRANSACTION
        const val CAPTURE_PERMISSION = IBinder.FIRST_CALL_TRANSACTION + 1
        const val DESTROY = 16777115
    }
}
