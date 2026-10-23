package io.github.fate_grand_automata.di.script

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import io.github.fate_grand_automata.scripts.models.AutoSkillCommand
import io.github.fate_grand_automata.scripts.models.CardPriorityPerWave
import io.github.fate_grand_automata.scripts.models.ServantPriorityPerWave
import io.github.fate_grand_automata.scripts.models.SpamConfigPerTeamSlot
import io.github.fate_grand_automata.scripts.prefs.IBattleConfig
import io.github.fate_grand_automata.runner.ScriptRunPreferences
import io.github.fate_grand_automata.scripts.decision.NativeBattleDecisionProvider
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferences
import io.github.fate_grand_automata.scripts.prefs.ISupportPreferencesCommon
import io.github.lib_automata.dagger.ScriptScope

@Module
@InstallIn(ScriptComponent::class)
class PreferencesModule {
    @ScriptScope
    @Provides
    fun provideDecisionProvider(preferences: ScriptRunPreferences): NativeBattleDecisionProvider = preferences.decisionProvider

    @ScriptScope
    @Provides
    fun provideBattleConfig(preferences: ScriptRunPreferences): IBattleConfig =
        preferences.prefs.selectedBattleConfig

    @ScriptScope
    @Provides
    fun provideSupportPrefs(battleConfig: IBattleConfig): ISupportPreferences =
        battleConfig.support

    @ScriptScope
    @Provides
    fun provideCommonSupportPrefs(preferences: ScriptRunPreferences): ISupportPreferencesCommon =
        preferences.prefs.support

    @ScriptScope
    @Provides
    fun provideSpamConfig(battleConfig: IBattleConfig, preferences: ScriptRunPreferences): SpamConfigPerTeamSlot =
        SpamConfigPerTeamSlot(if (preferences.decisionProvider.enabled) emptyList() else battleConfig.spam)

    @ScriptScope
    @Provides
    fun provideSkillCommand(battleConfig: IBattleConfig, preferences: ScriptRunPreferences): AutoSkillCommand =
        AutoSkillCommand.parse(if (preferences.decisionProvider.enabled) "" else battleConfig.skillCommand)

    @ScriptScope
    @Provides
    fun provideCardPriority(battleConfig: IBattleConfig, preferences: ScriptRunPreferences): CardPriorityPerWave =
        if (preferences.decisionProvider.enabled) CardPriorityPerWave.default else battleConfig.cardPriority

    @ScriptScope
    @Provides
    fun provideServantPriority(battleConfig: IBattleConfig, preferences: ScriptRunPreferences): ServantPriorityPerWave? =
        if (!preferences.decisionProvider.enabled && battleConfig.useServantPriority) battleConfig.servantPriority else null
}
