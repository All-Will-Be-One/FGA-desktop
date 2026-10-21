package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.*

class NativeSkillsReturnTest {
    @Test fun `Back delegates once without adding a matcher or timeout prerequisite`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val back = Location(2437, 1110)
        every { api.locations.attack.backClick } returns back
        api.returnToExternalSkills()
        verify(exactly = 1) { with(api) { back.click(1) } }
        verify(exactly = 0) { api.images }
        verify(exactly = 0) { api.useSameSnapIn<Unit>(any()) }
    }
    @Test fun `real cancelled native Back is propagated and never retried`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val back = Location(2437, 1110)
        every { api.locations.attack.backClick } returns back
        every { with(api) { back.click(1) } } throws ScriptAbortException()
        assertFailsWith<ScriptAbortException> { api.returnToExternalSkills() }
        verify(exactly = 1) { with(api) { back.click(1) } }
    }
}
