package io.github.fate_grand_automata.automation.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import io.github.fga.automation.RpcFailure
import kotlinx.serialization.json.*
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        return worker(context, AccessibilityUserService.SET_ENABLED) { it.writeInt(if (enabled) 1 else 0) }
    }

    fun capturePermission(context: Context, mode: String): String {
        CapturePermissionMode.validate(mode)
        return worker(context, AccessibilityUserService.CAPTURE_PERMISSION) { it.writeString(mode) }
    }

    private fun worker(context: Context, transaction: Int, write: (Parcel) -> Unit): String {
        if (!Shizuku.pingBinder()) throw RpcFailure("ShizukuUnavailable", "Shizuku is not running")
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw RpcFailure("RequiresUserConsent", "Explicit Shizuku client permission is required")
        if (Shizuku.getUid() != 2000) throw RpcFailure("UnsupportedBackend", "Only the ADB/shell Shizuku backend is supported")
        val args = Shizuku.UserServiceArgs(ComponentName(context, AccessibilityUserService::class.java))
            .tag("fga-own-accessibility-v1").version(1).daemon(false).processNameSuffix("own_accessibility")
        val connected = CountDownLatch(1)
        var binder: IBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service; connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { binder = null; connected.countDown() }
        }
        try {
            Shizuku.bindUserService(args, connection)
            if (!connected.await(5, TimeUnit.SECONDS) || binder == null)
                throw RpcFailure("ShizukuUnavailable", "The shell worker did not connect")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(AccessibilityUserService.DESCRIPTOR)
                write(data)
                check(requireNotNull(binder).transact(transaction, data, reply, 0))
                reply.readException()
                return reply.readString() ?: ""
            } finally { data.recycle(); reply.recycle() }
        } finally { Shizuku.unbindUserService(args, connection, true) }
    }
}
