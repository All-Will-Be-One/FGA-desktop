package io.github.fate_grand_automata.runner

enum class NativeAutomationState { Starting, Running, Paused, Stopping }

interface NativeAutomationListener {
    fun onStateChanged(state: NativeAutomationState)
    fun onExit(exit: NativeAutomationExit)
    /** A diagnostic only: the runner remains occupied and no terminal/quiescent event is emitted. */
    fun onCleanupFailure(error: String) {}
}

data class NativeAutomationExit(
    val reason: String,
    val completedRuns: Int,
    val error: String? = null,
    val outcome: String? = null,
    val withdrawCount: Int = 0
)

internal fun nativeBattleOutcome(reason: String, completedRuns: Int, maxRuns: Int?, withdrawCount: Int): String? = when {
    reason == "WithdrawDisabled" -> "defeated"
    withdrawCount > 0 -> "retreated"
    reason == "LimitRuns" && maxRuns != null && completedRuns >= maxRuns -> "target_reached"
    reason == "APRanOut" -> "ap_empty"
    else -> null
}

internal fun startupDeadlineExpired(deadlineElapsedMillis: Long?, nowElapsedMillis: Long): Boolean =
    deadlineElapsedMillis != null && nowElapsedMillis >= deadlineElapsedMillis

/** Called off the script thread; listener delivery alone does not prove that thread terminated. */
internal fun awaitNativeThreadTermination(thread: Thread?, timeoutMillis: Long): Boolean {
    if (thread == null || thread === Thread.currentThread()) return false
    require(timeoutMillis > 0)
    return try {
        thread.join(timeoutMillis)
        !thread.isAlive
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

sealed class NativeStartResult {
    data object Accepted : NativeStartResult()
    data object Busy : NativeStartResult()
    data object RequiresUserConsent : NativeStartResult()
    data class Rejected(val code: String, val message: String) : NativeStartResult()
}

/** Reserves the native runner before asynchronous preparation, including the interactive picker. */
internal class ScriptRunGate {
    enum class Owner { Interactive, Automation }
    class Reservation internal constructor(val owner: Owner) {
        internal var stopRequested = false
    }

    private var current: Reservation? = null
    private val idleCleanup = mutableListOf<() -> Unit>()

    /** Destroyed idle services must not close singleton image buffers used by an older executor. */
    @Synchronized
    fun afterCurrentRun(cleanup: () -> Unit) {
        if (current == null) cleanup() else idleCleanup += cleanup
    }

    @Synchronized
    fun reserve(owner: Owner): Reservation? {
        if (current != null) return null
        return Reservation(owner).also { current = it }
    }

    @Synchronized
    fun isAutomationBusy() = current?.owner == Owner.Automation

    @Synchronized
    fun currentReservation(automationOnly: Boolean = false): Reservation? =
        current?.takeIf { !automationOnly || it.owner == Owner.Automation }

    @Synchronized
    fun withReservation(reservation: Reservation, action: () -> Unit): Boolean {
        if (current !== reservation) return false
        action()
        return true
    }

    @Synchronized
    fun canPrepare(reservation: Reservation) = current === reservation && !reservation.stopRequested

    /** The callback starts the native thread while stop is excluded from the start transition. */
    @Synchronized
    fun start(reservation: Reservation, start: () -> Unit): Boolean {
        if (!canPrepare(reservation)) return false
        start()
        return true
    }

    @Synchronized
    fun requestStop(automationOnly: Boolean = false, expected: Reservation? = null): Reservation? {
        val reservation = current ?: return null
        if (expected != null && reservation !== expected) return null
        if (automationOnly && reservation.owner != Owner.Automation) return null
        reservation.stopRequested = true
        return reservation
    }

    @Synchronized
    fun finish(reservation: Reservation): Boolean {
        if (current !== reservation) return false
        val cleanup = idleCleanup.toList()
        idleCleanup.clear()
        cleanup.forEach { it() }
        current = null
        return true
    }
}

/** A stopped/recreated Android service cannot create a second executor in the same process. */
internal object ProcessScriptRunGate {
    val gate = ScriptRunGate()
}
