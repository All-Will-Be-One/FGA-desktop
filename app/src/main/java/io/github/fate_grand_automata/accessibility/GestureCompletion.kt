package io.github.fate_grand_automata.accessibility

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class GestureFailure { AccessibilityUnavailable, DispatchRejected, Cancelled, CallbackTimedOut }

class AccessibilityGestureException(val failure: GestureFailure) : Exception("Accessibility gesture failed: $failure")

/** Tracks Android acknowledgements separately from the coroutine waiting for them. */
internal class GestureDrainTracker {
    class Dispatch internal constructor(internal val willContinue: Boolean) {
        internal var rejected = false
        internal var terminal = false
    }
    private val pending = mutableSetOf<Dispatch>()
    private var continuationHeld = false
    private var onQuiescent: (() -> Unit)? = null

    @Synchronized fun isQuiescent() = pending.isEmpty() && !continuationHeld

    @Synchronized fun begin(isContinuation: Boolean, willContinue: Boolean): Dispatch {
        check(pending.isEmpty()) { "Previous gesture dispatch is unconfirmed" }
        check(isContinuation == continuationHeld) { "Continued pointer state is unconfirmed" }
        return Dispatch(willContinue).also { pending += it }
    }

    fun submitted(dispatch: Dispatch, accepted: Boolean) {
        if (!accepted) update {
            if (!dispatch.terminal) {
                dispatch.rejected = true
                pending -= dispatch
                // Rejection does not acknowledge release of a previously continued pointer.
            }
        }
    }

    fun completed(dispatch: Dispatch) = terminal(dispatch, cancelled = false)
    fun cancelled(dispatch: Dispatch) = terminal(dispatch, cancelled = true)

    private fun terminal(dispatch: Dispatch, cancelled: Boolean) = update {
        if (!dispatch.rejected && !dispatch.terminal) {
            dispatch.terminal = true
            pending -= dispatch
            continuationHeld = !cancelled && dispatch.willContinue
        }
    }

    fun observeQuiescence(listener: (() -> Unit)?) {
        val notify = synchronized(this) {
            onQuiescent = listener
            if (isQuiescent()) listener else null
        }
        notify?.invoke()
    }

    private fun update(change: () -> Unit) {
        val notify = synchronized(this) {
            val wasQuiescent = isQuiescent()
            change()
            if (!wasQuiescent && isQuiescent()) onQuiescent else null
        }
        notify?.invoke()
    }
}

/** Dispatch completion is bounded even if Android rejects the gesture or never calls back. */
internal suspend fun awaitGestureCompletion(
    timeoutMillis: Long,
    tracker: GestureDrainTracker = GestureDrainTracker(),
    isContinuation: Boolean = false,
    willContinue: Boolean = false,
    dispatch: (completed: () -> Unit, cancelled: () -> Unit) -> Boolean
) {
    val tracked = tracker.begin(isContinuation, willContinue)
    val completion = CompletableDeferred<Unit>()
    val accepted = dispatch(
        { tracker.completed(tracked); completion.complete(Unit); Unit },
        { tracker.cancelled(tracked); completion.completeExceptionally(AccessibilityGestureException(GestureFailure.Cancelled)); Unit }
    )
    tracker.submitted(tracked, accepted)
    if (!accepted) throw AccessibilityGestureException(GestureFailure.DispatchRejected)
    try {
        withTimeout(timeoutMillis) { completion.await() }
    } catch (_: TimeoutCancellationException) {
        throw AccessibilityGestureException(GestureFailure.CallbackTimedOut)
    }
}
