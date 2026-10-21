package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.models.ServantTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NativeDeploymentTest {
    @Test
    fun `native recipient mapping is explicit including no target`() {
        assertNull(nativeServantTarget(null))
        assertEquals(ServantTarget.A, nativeServantTarget(1))
        assertEquals(ServantTarget.B, nativeServantTarget(2))
        assertEquals(ServantTarget.C, nativeServantTarget(3))
        assertFailsWith<NativeDecisionException> { nativeServantTarget(0) }
    }

}
