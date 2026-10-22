package io.github.fate_grand_automata.automation.platform

import kotlin.test.*

class CapturePermissionModeTest {
    @Test fun fixedModesExcludeCommandsAndOtherOperations() {
        for (mode in CapturePermissionMode.supported) CapturePermissionMode.validate(mode)
        for (mode in listOf("allow; id", "PROJECT_MEDIA", "WRITE_SECURE_SETTINGS", "", "ALLOW"))
            assertFailsWith<IllegalArgumentException> { CapturePermissionMode.validate(mode) }
    }
}
