package io.github.fate_grand_automata.automation.platform

import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import io.github.fga.automation.RpcFailure
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Android's existing Binder shell-command ABI, forwarded by the authorized Shizuku client. */
internal object ShizukuShell {
    // IBinder.SHELL_COMMAND_TRANSACTION ('_CMD'), Binder.java / BinderProxy.java.
    private const val SHELL_COMMAND = 0x5f434d44

    fun settings(args: List<String>) = execute("settings", args)
    fun appops(args: List<String>) = execute("appops", args)

    private fun execute(serviceName: String, args: List<String>): String {
        val service = SystemServiceHelper.getSystemService(serviceName)
            ?: throw RpcFailure("PlatformServiceUnavailable", "The system permission service is unavailable")
        val done = CountDownLatch(1)
        val exitCode = AtomicInteger(-1)
        val result = object : ResultReceiver(null) {
            override fun onReceiveResult(code: Int, data: Bundle?) { exitCode.set(code); done.countDown() }
        }
        val input = ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY)
        val pipe = ParcelFileDescriptor.createPipe()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            try {
                // Raw FDs / array / callback Binder / ResultReceiver: no AIDL interface token or presence markers.
                data.writeFileDescriptor(input.fileDescriptor)
                data.writeFileDescriptor(pipe[1].fileDescriptor)
                data.writeFileDescriptor(pipe[1].fileDescriptor)
                data.writeStringArray(args.toTypedArray())
                data.writeStrongBinder(null) // ShellCallback.writeToParcel(null, data)
                result.writeToParcel(data, 0)
                if (!ShizukuBinderWrapper(service).transact(SHELL_COMMAND, data, reply, 0))
                    throw RpcFailure("UnsupportedPlatform", "System permission service rejected the shell-command ABI")
                reply.readException()
            } finally {
                // Parcel owns duplicated write FDs; release them before waiting for pipe EOF.
                data.recycle(); reply.recycle()
            }
            pipe[1].close()
            val output = ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader().use { it.readText().trim() }
            if (!done.await(5, TimeUnit.SECONDS)) throw RpcFailure("PlatformResultUnavailable", "Permission command result did not arrive")
            if (exitCode.get() != 0) throw RpcFailure("PlatformCommandFailed", output.take(512))
            return output
        } finally {
            runCatching { input.close() }
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }
}
