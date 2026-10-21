package io.github.fate_grand_automata.ui.launcher

import io.github.fate_grand_automata.runner.BattleRunRequest

sealed class ScriptLauncherResponse {
    data object Cancel : ScriptLauncherResponse()
    data class FP(val limit: Int?) : ScriptLauncherResponse()
    data class Lottery(
        val giftBox: GiftBox?
    ) : ScriptLauncherResponse()

    data class GiftBox(
        val maxGoldEmberStackSize: Int,
        val maxGoldEmberTotalCount: Int
    ) : ScriptLauncherResponse()

    data class CEBomb(val targetRarity: Int) : ScriptLauncherResponse()
    data object SupportImageMaker : ScriptLauncherResponse()
    data class Battle(val request: BattleRunRequest) : ScriptLauncherResponse()

    data object ServantEnhancement : ScriptLauncherResponse()
}

class ScriptLauncherResponseBuilder(
    val canBuild: () -> Boolean,
    val build: () -> ScriptLauncherResponse
)
