package io.github.fate_grand_automata.automation.platform

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessibilityServiceListTest {
    private val own = "test.app/own.Service"
    @Test fun preservesOtherServicesAndOrder() {
        assertEquals("a/One:b/Two:$own", AccessibilityServiceList.update("a/One:b/Two", own, true))
        assertEquals("a/One:b/Two", AccessibilityServiceList.update("a/One:$own:b/Two", own, false))
    }
    @Test fun explicitEnableAndDisableAreIdempotent() {
        assertEquals(own, AccessibilityServiceList.update(own, own, true))
        assertEquals("other/Service", AccessibilityServiceList.update("other/Service", own, false))
        assertEquals(null, AccessibilityServiceList.update(null, own, false))
        assertEquals(own, AccessibilityServiceList.update(null, own, true))
        assertEquals("", AccessibilityServiceList.update(own, own, false))
    }
    @Test fun handlesLegalShortComponentsWithoutRewritingOtherServices() {
        val full = "test.app/test.app.Service"
        val short = "test.app/.Service"
        val raw = "other/.One:$short:third/.Two"
        assertTrue(AccessibilityServiceList.contains(raw, full))
        assertEquals(raw, AccessibilityServiceList.update(raw, full, true))
        assertEquals("other/.One:third/.Two", AccessibilityServiceList.update(raw, full, false))
    }
}
