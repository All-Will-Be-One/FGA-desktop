package io.github.fate_grand_automata.automation.platform

/** State of the existing Android permission launcher, not battle state. */
data class CaptureConsent(
    val pending: Boolean = false,
    val closed: Boolean = false,
    val acceptResult: Boolean = false
) {
    fun begin() = if (pending) this else copy(pending = true, closed = false, acceptResult = true)
    fun close() = copy(closed = true, acceptResult = false)
    fun finish(granted: Boolean) = copy(pending = false, closed = closed || !granted, acceptResult = false)
}
