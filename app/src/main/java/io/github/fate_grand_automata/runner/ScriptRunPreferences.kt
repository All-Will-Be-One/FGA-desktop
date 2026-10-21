package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.RefillResourceEnum
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPerServerConfigPrefs
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.fate_grand_automata.scripts.decision.NativeBattleDecisionProvider
import io.github.fate_grand_automata.scripts.decision.DisabledNativeBattleDecisionProvider

/** Bound per ScriptComponent so native decisions never read a transient UI preference value. */
class ScriptRunPreferences(
    val prefs: IPreferences,
    val decisionProvider: NativeBattleDecisionProvider = DisabledNativeBattleDecisionProvider
) {
    companion object {
        fun automation(original: IPreferences, validated: ValidatedBattleRun,
                       decisionProvider: NativeBattleDecisionProvider = DisabledNativeBattleDecisionProvider): ScriptRunPreferences {
            val request = validated.request
            require(decisionProvider.enabled == (request.decisionMode == "smart")) { "Decision mode and native provider do not match" }
            val fixedConfig = object : IBattleConfig by validated.config {
                override val id = request.configId
                override val server = request.server
                override var skillCommand: String
                    get() = validated.skillCommand
                    set(@Suppress("UNUSED_PARAMETER") value) = error("Automation skill command is frozen")
            }
            val fixedServer = AutomationServerPreferences(request)
            return ScriptRunPreferences(object : IPreferences by original {
                override var gameServer: GameServer
                    get() = request.server
                    set(value) { require(value == request.server) { "Automation server is frozen" } }
                override var selectedServerConfigPref: IPerServerConfigPrefs
                    get() = fixedServer
                    set(value) { require(value === fixedServer) { "Automation server preferences are frozen" } }
                override var selectedBattleConfig: IBattleConfig
                    get() = fixedConfig
                    set(value) { require(value === fixedConfig) { "Automation configuration is frozen" } }
                override val battleConfigs = listOf(fixedConfig)
                override val boostItemSelectionMode = -1
                override val withdrawEnabled = false
                override var stopAfterThisRun = false
                override fun getPerServerConfigPref(server: GameServer): IPerServerConfigPrefs {
                    require(server.simple == request.server.simple) { "Automation server is frozen" }
                    return fixedServer
                }
                override fun forBattleConfig(id: String): IBattleConfig {
                    require(id == request.configId) { "Automation configuration is frozen" }
                    return fixedConfig
                }
            }, decisionProvider)
        }
    }
}

private class AutomationServerPreferences(private val request: BattleRunRequest) : IPerServerConfigPrefs {
    override val server = request.server
    override var selectedAutoSkillKey = request.configId
    override var rainbowApple = 0
    override var goldApple = 0
    override var silverApple = 0
    override var blueApple = 0
    override var copperApple = 0
    override var currentAppleCount = 0
    override var selectedApple = RefillResourceEnum.Copper
    override var waitForAPRegen: Boolean
        get() = false
        set(value) { require(!value) { "Automation cannot wait for AP" } }
    override val resources = emptyList<RefillResourceEnum>()
    override fun updateResources(resources: Set<RefillResourceEnum>) {
        require(resources.isEmpty()) { "Automation cannot enable refills" }
    }
    override var shouldLimitRuns = true
    override var limitRuns = request.limits.limitRuns
        set(value) { require(value <= request.limits.limitRuns) { "Automation run limit cannot increase" }; field = value }
    override var shouldLimitMats = request.limits.shouldLimitMaterials
    override var limitMats = request.limits.limitMaterials
        set(value) { require(value <= request.limits.limitMaterials) { "Automation material limit cannot increase" }; field = value }
    override var shouldLimitCEs = request.limits.shouldLimitCEs
    override var limitCEs = request.limits.limitCEs
        set(value) { require(value <= request.limits.limitCEs) { "Automation CE limit cannot increase" }; field = value }
}
