package io.github.fate_grand_automata.automation.platform

/** Preserve other services; there is no automatic re-enable or background watcher. */
object AccessibilityServiceList {
    private fun canonical(component: String): String {
        val parts = component.split('/', limit = 2)
        if (parts.size != 2) return component
        return parts[0] + "/" + if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1]
    }
    fun contains(raw: String?, own: String): Boolean =
        raw?.split(':')?.any { canonical(it) == canonical(own) } == true

    fun update(raw: String?, own: String, enabled: Boolean): String? {
        val entries = raw?.split(':')?.filter { it.isNotBlank() } ?: emptyList()
        if (enabled) return if (contains(raw, own)) raw else (entries + own).joinToString(":")
        return if (!contains(raw, own)) raw else entries.filter { canonical(it) != canonical(own) }.joinToString(":")
    }
}
