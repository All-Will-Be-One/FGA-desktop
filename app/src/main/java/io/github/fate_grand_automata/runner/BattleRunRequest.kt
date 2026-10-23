package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.enums.RefillResourceEnum
import io.github.fate_grand_automata.scripts.enums.ScriptModeEnum
import io.github.fate_grand_automata.scripts.models.AutoSkillAction
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.scripts.prefs.IPerServerConfigPrefs
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import javax.inject.Inject

/** The same launch settings are used by the interactive launcher and the local automation API. */
data class BattleRunRequest(
    val configId: String,
    val server: GameServer,
    val limits: BattleRunLimits,
    val refills: BattleRefills,
    val waitForApRegen: Boolean,
    val resetCompletedRuns: Boolean = true,
    val timeoutSeconds: Long? = null,
    /** Android elapsedRealtime deadline supplied by the bridge, including time queued to start. */
    val startDeadlineElapsedRealtimeMillis: Long? = null,
    val decisionMode: String = "script"
)

data class BattleRunLimits(
    val shouldLimitRuns: Boolean,
    val limitRuns: Int,
    val shouldLimitMaterials: Boolean,
    val limitMaterials: Int,
    val shouldLimitCEs: Boolean,
    val limitCEs: Int
)

data class BattleRefills(
    val resources: Set<RefillResourceEnum>,
    val copper: Int,
    val bronze: Int,
    val silver: Int,
    val gold: Int,
    val saintQuartz: Int
)

enum class BattleRunPolicy { Interactive, Automation }

class BattleRunValidationException(val code: String, message: String) : IllegalArgumentException(message)

class ValidatedBattleRun internal constructor(
    val request: BattleRunRequest,
    internal val config: IBattleConfig,
    internal val skillCommand: String
)

/** Validates everything before changing preferences, so rejected requests cannot partially apply. */
class BattleRunRequestHandler @Inject constructor(private val prefs: IPreferences) {
    fun snapshot(configId: String, server: GameServer): BattleRunRequest {
        val perServer = prefs.getPerServerConfigPref(server)
        return BattleRunRequest(
            configId = configId,
            server = server,
            limits = BattleRunLimits(
                perServer.shouldLimitRuns, perServer.limitRuns,
                perServer.shouldLimitMats, perServer.limitMats,
                perServer.shouldLimitCEs, perServer.limitCEs
            ),
            refills = perServer.refillSnapshot(),
            waitForApRegen = perServer.waitForAPRegen
        )
    }

    fun validate(request: BattleRunRequest, policy: BattleRunPolicy): ValidatedBattleRun {
        fun requireValid(condition: Boolean, code: String, message: String) {
            if (!condition) throw BattleRunValidationException(code, message)
        }
        requireValid(request.decisionMode in setOf("script", "smart"), "InvalidDecisionMode", "Unknown battle decision mode")
        requireValid(policy == BattleRunPolicy.Automation || request.decisionMode == "script",
            "InvalidDecisionMode", "Interactive launches use script decisions")

        val config = prefs.battleConfigs.firstOrNull { it.id == request.configId }
            ?: throw BattleRunValidationException("UnknownConfig", "Battle configuration does not exist")
        val skillCommand = if (request.decisionMode == "smart") "" else config.skillCommand
        val configServer = config.server
        requireValid(
            configServer == null || configServer.simple == request.server.simple,
            "ServerMismatch", "Battle configuration belongs to a different game server"
        )
        with(request.limits) {
            requireValid(listOf(shouldLimitRuns to limitRuns, shouldLimitMaterials to limitMaterials,
                shouldLimitCEs to limitCEs).all { (enabled, value) -> !enabled || value in 1..99_999 },
                "InvalidLimits", "Battle limits must be between 1 and 99999")
        }
        with(request.refills) {
            requireValid(resources.size <= 1, "InvalidRefills", "Select at most one refill resource")
            val selectedCount = when (resources.firstOrNull()) {
                RefillResourceEnum.Copper -> copper
                RefillResourceEnum.Bronze -> bronze
                RefillResourceEnum.Silver -> silver
                RefillResourceEnum.Gold -> gold
                RefillResourceEnum.SQ -> saintQuartz
                null -> 0
            }
            requireValid(selectedCount in 0..999,
                "InvalidRefills", "Refill counts must be between 0 and 999")
        }
        if (policy == BattleRunPolicy.Automation) {
            requireValid(request.limits.shouldLimitRuns, "UnboundedRun", "Automation requires a finite run limit")
            requireValid(request.timeoutSeconds != null && request.timeoutSeconds in 1L..86_400L,
                "InvalidTimeout", "Automation requires a timeout between 1 and 86400 seconds")
            requireValid(request.resetCompletedRuns, "InvalidReset", "Automation must reset the completed run count")
            requireValid(RefillResourceEnum.SQ !in request.refills.resources,
                "ForbiddenSaintQuartz", "Saint Quartz refills are not allowed by automation")
            requireValid(request.refills.resources.isEmpty(), "ForbiddenRefills", "Refills are not allowed by automation")
            requireValid(!request.waitForApRegen, "ForbiddenApWait", "AP regeneration waits are not allowed by automation")
            // The external Smart path never executes legacy auto-skill commands.
            if (request.decisionMode == "script") {
                val commands = try {
                    AutoSkillCommand.parse(skillCommand)
                } catch (_: Exception) {
                    throw BattleRunValidationException("InvalidSkillCommand", "Battle skill command cannot be parsed")
                }
                requireValid(commands.stages.flatten().flatten().none { it is AutoSkillAction.CommandSpell },
                    "ForbiddenCommandSpell", "Command spells are not allowed by automation")
            }
        }
        return ValidatedBattleRun(request.copy(refills = request.refills.copy(resources = request.refills.resources.toSet())),
            config, skillCommand)
    }

    fun apply(validated: ValidatedBattleRun) {
        val request = validated.request
        prefs.gameServer = request.server
        val perServer = prefs.getPerServerConfigPref(request.server)
        prefs.selectedServerConfigPref = perServer
        with(request.limits) {
            perServer.shouldLimitRuns = shouldLimitRuns
            if (shouldLimitRuns) perServer.limitRuns = limitRuns
            perServer.shouldLimitMats = shouldLimitMaterials
            if (shouldLimitMaterials) perServer.limitMats = limitMaterials
            perServer.shouldLimitCEs = shouldLimitCEs
            if (shouldLimitCEs) perServer.limitCEs = limitCEs
        }
        with(request.refills) {
            when (resources.firstOrNull()) {
                RefillResourceEnum.Copper -> perServer.copperApple = copper
                RefillResourceEnum.Bronze -> perServer.blueApple = bronze
                RefillResourceEnum.Silver -> perServer.silverApple = silver
                RefillResourceEnum.Gold -> perServer.goldApple = gold
                RefillResourceEnum.SQ -> perServer.rainbowApple = saintQuartz
                null -> Unit
            }
            resources.firstOrNull()?.let { perServer.selectedApple = it }
            perServer.updateResources(resources)
        }
        perServer.waitForAPRegen = request.waitForApRegen
        prefs.selectedBattleConfig = validated.config
        prefs.scriptMode = ScriptModeEnum.Battle
        if (request.resetCompletedRuns) prefs.resetCompletedRuns()
    }

    fun apply(request: BattleRunRequest, policy: BattleRunPolicy = BattleRunPolicy.Interactive) =
        apply(validate(request, policy))
}

private fun IPerServerConfigPrefs.refillSnapshot() = BattleRefills(
    resources.toSet(), copperApple, blueApple, silverApple, goldApple, rainbowApple
)
