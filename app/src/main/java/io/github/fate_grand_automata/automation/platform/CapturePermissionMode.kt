package io.github.fate_grand_automata.automation.platform

object CapturePermissionMode {
    val supported = setOf("read", "allow", "ignore", "deny", "default", "foreground")
    fun validate(value: String) { require(value in supported) { "Unsupported PROJECT_MEDIA mode" } }
}
