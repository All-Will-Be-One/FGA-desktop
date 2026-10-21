package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.models.FieldSlot
import io.github.fate_grand_automata.scripts.models.ServantTarget
import io.github.fate_grand_automata.scripts.models.TeamSlot
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

    @Test
    fun `present single recipient preserves upstream layout remapping but absent logical slot cannot default`() {
        val deployed: Map<FieldSlot, TeamSlot> = mapOf(FieldSlot.C to TeamSlot.C)
        validateNativeDeployment(NativeDecisionAction.ServantSkill(2, 0, 3), deployed, emptyList())
        validateNativeDeployment(NativeDecisionAction.MasterSkill(0, 3), deployed, emptyList())
        assertFailsWith<IllegalArgumentException> {
            validateNativeDeployment(NativeDecisionAction.MasterSkill(0, 2), deployed, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            validateNativeDeployment(NativeDecisionAction.ServantSkill(0, 0), deployed, emptyList())
        }
    }

    @Test
    fun `order change requires declared known front and current reserve position`() {
        val deployed: Map<FieldSlot, TeamSlot> = mapOf(FieldSlot.B to TeamSlot.B)
        validateNativeDeployment(NativeDecisionAction.OrderChange(1, 1), deployed, listOf(TeamSlot.D, TeamSlot.E))
        assertFailsWith<IllegalArgumentException> {
            validateNativeDeployment(NativeDecisionAction.OrderChange(1, 2), deployed, listOf(TeamSlot.D, TeamSlot.E))
        }
        assertFailsWith<IllegalArgumentException> {
            validateNativeDeployment(NativeDecisionAction.OrderChange(1, 0), deployed, listOf(TeamSlot.Unknown))
        }
        assertFailsWith<IllegalArgumentException> {
            validateNativeDeployment(NativeDecisionAction.OrderChange(0, 0), deployed, listOf(TeamSlot.D))
        }
        assertFailsWith<IllegalArgumentException> {
            validateNativeDeployment(NativeDecisionAction.ServantSkill(0, 0), mapOf(FieldSlot.A to TeamSlot.Unknown), emptyList())
        }
    }
}
