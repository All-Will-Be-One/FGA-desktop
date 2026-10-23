package io.github.fate_grand_automata.automation

import io.github.fate_grand_automata.runner.BattleRunValidationException
import io.github.fate_grand_automata.scripts.enums.GameServer
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Shared by the bridge's real inventory and runner dispatch path; no policy parsing. */
internal fun automationConfig(configs:List<IBattleConfig>,id:String):IBattleConfig =
    configs.firstOrNull { it.id==id }
        ?: throw BattleRunValidationException("UnknownConfig","Battle configuration does not exist")

internal fun automationConfigurations(configs:List<IBattleConfig>,resolveServer:(IBattleConfig)->GameServer?,
    region:(GameServer)->String):JsonObject = buildJsonObject {
    putJsonArray("configs") {
        configs.forEach { config ->
            val scriptHash=runCatching { MessageDigest.getInstance("SHA-256").digest(config.skillCommand.toByteArray())
                .joinToString("") { "%02x".format(it) } }
            add(buildJsonObject {
                put("id",config.id);put("name",config.name)
                put("server",resolveServer(config)?.let { JsonPrimitive(region(it)) } ?: JsonNull)
                put("scriptSha256",scriptHash.getOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                scriptHash.exceptionOrNull()?.let { put("scriptHashWarning",it.message ?: it.javaClass.simpleName) }
            })
        }
    }
}
