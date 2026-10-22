package io.github.fate_grand_automata.automation.platform

import rikka.shizuku.ShizukuProvider

/** This integration uses the existing ADB/shell backend, never Sui/root. */
class ShellShizukuProvider : ShizukuProvider() {
    override fun onCreate(): Boolean {
        disableAutomaticSuiInitialization()
        return super.onCreate()
    }
}
