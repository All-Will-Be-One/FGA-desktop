package io.github.fate_grand_automata.runner

import io.github.fate_grand_automata.di.script.PreferencesModule
import io.github.fate_grand_automata.scripts.prefs.*
import io.github.fate_grand_automata.scripts.decision.*
import java.lang.reflect.Proxy
import kotlin.test.*

class SmartPreferenceProvidersTest {
    private inline fun <reified T> unreadable(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unused field read: ${method.name}")
    } as T

    @Test fun `external mode builds all unused policy providers without any config getters`() {
        val provider = object : NativeBattleDecisionProvider by DisabledNativeBattleDecisionProvider { override val enabled = true }
        val preferences = ScriptRunPreferences(unreadable<IPreferences>(), provider)
        val config = unreadable<IBattleConfig>(); val module = PreferencesModule()
        assertTrue(module.provideSkillCommand(config, preferences).stages.flatten().flatten().isEmpty())
        module.provideSpamConfig(config, preferences)
        assertTrue(module.provideCardPriority(config, preferences).isNotEmpty())
        assertNull(module.provideServantPriority(config, preferences))
        assertFailsWith<IllegalStateException> { module.provideSkillCommand(config, ScriptRunPreferences(unreadable<IPreferences>())) }
    }
}
