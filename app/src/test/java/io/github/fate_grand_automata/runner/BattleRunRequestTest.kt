package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.RefillResourceEnum
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPerServerConfigPrefs
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.decision.NativeBattleDecisionProvider
import io.github.fate_grand_automata.scripts.decision.DisabledNativeBattleDecisionProvider
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleRunRequestTest {
    private class ServerPrefs(override val server: GameServer) : IPerServerConfigPrefs {
        override var selectedAutoSkillKey = ""
        override var rainbowApple = 0
        override var goldApple = 0
        override var silverApple = 0
        override var blueApple = 0
        override var copperApple = 0
        override var waitForAPRegen = false
        override var selectedApple = RefillResourceEnum.Gold
        override var currentAppleCount = 0
        override var resources = emptyList<RefillResourceEnum>()
        override fun updateResources(resources: Set<RefillResourceEnum>) { this.resources = resources.toList() }
        override var shouldLimitRuns = false
        override var limitRuns = 1
        override var shouldLimitMats = false
        override var limitMats = 1
        override var shouldLimitCEs = false
        override var limitCEs = 1
    }

    private class Fixture {
        var configServer: GameServer? = GameServer.Jp.Original
        var command = "a1,4,#,b2,5"
        var boost = -1
        var withdraw = false
        var configsPresent = true
        val writes = mutableListOf<String>()
        val serverPrefs = ServerPrefs(GameServer.Jp.Original)
        var server: GameServer = GameServer.En.Original
        var selectedConfig: IBattleConfig? = null
        var resetCount = 0
        val config: IBattleConfig = proxy { name, _ ->
            when (name) {
                "getId" -> "existing"
                "getServer" -> configServer
                "getSkillCommand" -> command
                else -> error("Unexpected config call: $name")
            }
        }
        val prefs: IPreferences = proxy { name, args ->
            when (name) {
                "getBattleConfigs" -> if (configsPresent) listOf(config) else emptyList()
                "getBoostItemSelectionMode" -> boost
                "getWithdrawEnabled" -> withdraw
                "getPerServerConfigPref" -> serverPrefs.also { assertEquals("Jp", (args[0] as GameServer).simple) }
                "setGameServer" -> { writes += name; server = args[0] as GameServer; Unit }
                "setSelectedServerConfigPref", "setScriptMode" -> { writes += name; Unit }
                "setSelectedBattleConfig" -> { writes += name; selectedConfig = args[0] as IBattleConfig; Unit }
                "resetCompletedRuns" -> { writes += name; resetCount++; Unit }
                else -> error("Unexpected preferences call: $name")
            }
        }
        val handler = BattleRunRequestHandler(prefs)

        fun request() = BattleRunRequest(
            "existing", GameServer.Jp.Original,
            BattleRunLimits(true, 5, true, 7, false, 9),
            BattleRefills(emptySet(), 1, 2, 3, 4, 5),
            waitForApRegen = false,
            timeoutSeconds = 60
        )
    }

    @Test
    fun `interactive and automation use the same exact preference application`() {
        for (policy in BattleRunPolicy.entries) {
            val f = Fixture()
            f.handler.apply(f.request(), policy)
            with(f.serverPrefs) {
                assertTrue(shouldLimitRuns)
                assertEquals(5, limitRuns)
                assertTrue(shouldLimitMats)
                assertEquals(7, limitMats)
                assertFalse(shouldLimitCEs)
                assertEquals(9, limitCEs)
                assertEquals(listOf(1, 2, 3, 4, 5), listOf(copperApple, blueApple, silverApple, goldApple, rainbowApple))
                assertEquals(emptyList(), resources)
                assertEquals(RefillResourceEnum.Gold, selectedApple)
                assertFalse(waitForAPRegen)
            }
            assertEquals(GameServer.Jp.Original, f.server)
            assertTrue(f.selectedConfig === f.config)
            assertEquals(1, f.resetCount)
        }
    }

    @Test
    fun `interactive settings preserve refill selection AP wait and reset choice`() {
        val f = Fixture()
        f.boost = 2
        f.handler.apply(f.request().let {
            it.copy(refills = it.refills.copy(resources = setOf(RefillResourceEnum.SQ)),
                waitForApRegen = true, resetCompletedRuns = false)
        })
        assertEquals(listOf(RefillResourceEnum.SQ), f.serverPrefs.resources)
        assertEquals(RefillResourceEnum.SQ, f.serverPrefs.selectedApple)
        assertTrue(f.serverPrefs.waitForAPRegen)
        assertEquals(0, f.resetCount)
    }

    @Test
    fun `snapshot preserves disabled limits and every refill count`() {
        val f = Fixture()
        f.handler.apply(f.request())
        val snapshot = f.handler.snapshot("existing", GameServer.Jp.Original)
        assertEquals(f.request().copy(timeoutSeconds = null), snapshot)
    }

    @Test
    fun `shared application preserves BetterFGO server variant`() {
        val f = Fixture()
        f.handler.apply(f.request().copy(server = GameServer.Jp.BetterFGO))
        assertEquals(GameServer.Jp.BetterFGO, f.server)
    }

    @Test
    fun `missing configs and server mismatches reject before all preference writes`() {
        val f = Fixture()
        f.configsPresent = false
        assertEquals("UnknownConfig", assertFailsWith<BattleRunValidationException> {
            f.handler.apply(f.request(), BattleRunPolicy.Automation)
        }.code)
        f.configsPresent = true
        f.configServer = GameServer.Cn
        assertEquals("ServerMismatch", assertFailsWith<BattleRunValidationException> {
            f.handler.apply(f.request(), BattleRunPolicy.Automation)
        }.code)
        assertTrue(f.writes.isEmpty())
        assertEquals(1, f.serverPrefs.limitRuns)
    }

    @Test
    fun `automation rejects command spells embedded in a later parsed wave`() {
        val f = Fixture()
        f.command = "a1,4,#,b2,o3,5"
        assertEquals("ForbiddenCommandSpell", assertFailsWith<BattleRunValidationException> {
            f.handler.apply(f.request(), BattleRunPolicy.Automation)
        }.code)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `automation rejects malformed commands without applying any settings`() {
        val f = Fixture()
        f.command = "invalid!"
        assertEquals("InvalidSkillCommand", assertFailsWith<BattleRunValidationException> {
            f.handler.apply(f.request(), BattleRunPolicy.Automation)
        }.code)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `automation disallows all refills AP wait boost and unbounded execution`() {
        val f = Fixture()
        val request = f.request()
        val rejected = listOf(
            request.copy(refills = request.refills.copy(resources = setOf(RefillResourceEnum.SQ))) to "ForbiddenSaintQuartz",
            request.copy(refills = request.refills.copy(resources = setOf(RefillResourceEnum.Gold))) to "ForbiddenRefills",
            request.copy(waitForApRegen = true) to "ForbiddenApWait",
            request.copy(limits = request.limits.copy(shouldLimitRuns = false)) to "UnboundedRun",
            request.copy(timeoutSeconds = null) to "InvalidTimeout",
            request.copy(timeoutSeconds = 0) to "InvalidTimeout",
            request.copy(resetCompletedRuns = false) to "InvalidReset"
        )
        for ((candidate, expected) in rejected) {
            assertEquals(expected, assertFailsWith<BattleRunValidationException> {
                f.handler.apply(candidate, BattleRunPolicy.Automation)
            }.code)
        }
        f.boost = 0
        assertEquals("ForbiddenBoost", assertFailsWith<BattleRunValidationException> {
            f.handler.apply(request, BattleRunPolicy.Automation)
        }.code)
        f.boost = -1
        f.withdraw = true
        assertEquals("ForbiddenAutoWithdraw", assertFailsWith<BattleRunValidationException> {
            f.handler.apply(request, BattleRunPolicy.Automation)
        }.code)
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `negative and excessive counters are never partially applied`() {
        val f = Fixture()
        val request = f.request()
        for (candidate in listOf(
            request.copy(limits = request.limits.copy(limitRuns = 0)),
            request.copy(refills = request.refills.copy(copper = -1)),
            request.copy(refills = request.refills.copy(gold = 1000))
        )) {
            assertFailsWith<BattleRunValidationException> { f.handler.apply(candidate) }
        }
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `validated refill resources cannot be changed by the request caller`() {
        val f = Fixture()
        val resources = mutableSetOf<RefillResourceEnum>()
        val validated = f.handler.validate(f.request().let { it.copy(refills = it.refills.copy(resources = resources)) },
            BattleRunPolicy.Automation)
        resources += RefillResourceEnum.SQ
        f.handler.apply(validated)
        assertTrue(f.serverPrefs.resources.isEmpty())
    }

    @Test
    fun `native component preferences freeze paid actions commands server and finite limits`() {
        val f = Fixture()
        val validated = f.handler.validate(f.request(), BattleRunPolicy.Automation)
        val frozen = ScriptRunPreferences.automation(f.prefs, validated).prefs
        val originalCommand = f.command
        // A transient UI edit cannot become a native decision, even if restored before a guard.
        f.command = "o1"
        f.boost = 2
        f.withdraw = true
        f.serverPrefs.resources = listOf(RefillResourceEnum.SQ)
        f.serverPrefs.waitForAPRegen = true
        f.serverPrefs.shouldLimitRuns = false
        f.serverPrefs.limitRuns = 99_999
        f.configServer = GameServer.Cn
        assertEquals(originalCommand, frozen.selectedBattleConfig.skillCommand)
        assertEquals(GameServer.Jp.Original, frozen.gameServer)
        assertEquals(GameServer.Jp.Original, frozen.selectedBattleConfig.server)
        assertEquals(-1, frozen.boostItemSelectionMode)
        assertFalse(frozen.withdrawEnabled)
        with(frozen.selectedServerConfigPref) {
            assertTrue(resources.isEmpty())
            assertFalse(waitForAPRegen)
            assertTrue(shouldLimitRuns)
            assertEquals(5, limitRuns)
            assertEquals(7, limitMats)
            assertEquals(9, limitCEs)
            assertEquals(0, currentAppleCount)
        }
        assertFailsWith<IllegalStateException> { frozen.selectedBattleConfig.skillCommand = "o1" }
        assertFailsWith<IllegalArgumentException> {
            frozen.selectedServerConfigPref.updateResources(setOf(RefillResourceEnum.Gold))
        }
        assertFailsWith<IllegalArgumentException> { frozen.selectedServerConfigPref.waitForAPRegen = true }
        assertFailsWith<IllegalArgumentException> { frozen.selectedServerConfigPref.limitRuns = 6 }
        assertFailsWith<IllegalArgumentException> { frozen.gameServer = GameServer.Cn }
        frozen.selectedServerConfigPref.limitRuns = 0
        frozen.selectedServerConfigPref.limitRuns = 1 // Upstream's post-run limit reset is local and bounded.
        assertTrue(f.writes.isEmpty())
    }

    @Test
    fun `dynamic mode is explicit validated and cannot silently fall back to scripts`() {
        val f = Fixture()
        val smart = f.handler.validate(f.request().copy(decisionMode = "smart"), BattleRunPolicy.Automation)
        assertEquals("script", f.request().decisionMode)
        assertFailsWith<IllegalArgumentException> { ScriptRunPreferences.automation(f.prefs, smart) }
        val external = object : NativeBattleDecisionProvider by DisabledNativeBattleDecisionProvider {
            override val enabled = true
        }
        assertTrue(ScriptRunPreferences.automation(f.prefs, smart, external).decisionProvider === external)
        val script = f.handler.validate(f.request(), BattleRunPolicy.Automation)
        assertFalse(ScriptRunPreferences.automation(f.prefs, script).decisionProvider.enabled)
        assertFalse(ScriptRunPreferences(f.prefs).decisionProvider.enabled)
        assertFailsWith<IllegalArgumentException> { ScriptRunPreferences.automation(f.prefs, script, external) }
        assertEquals("InvalidDecisionMode", assertFailsWith<BattleRunValidationException> {
            f.handler.validate(f.request().copy(decisionMode = "auto"), BattleRunPolicy.Automation)
        }.code)
        assertEquals("InvalidDecisionMode", assertFailsWith<BattleRunValidationException> {
            f.handler.validate(f.request().copy(decisionMode = "smart"), BattleRunPolicy.Interactive)
        }.code)
        assertEquals("UnsupportedSmartServer", assertFailsWith<BattleRunValidationException> {
            f.handler.validate(f.request().copy(decisionMode = "smart", server = GameServer.Kr), BattleRunPolicy.Automation)
        }.code)
        assertTrue(f.writes.isEmpty())
    }

    companion object {
        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            } as T
    }
}
