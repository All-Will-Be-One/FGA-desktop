package io.github.fate_grand_automata.di.script

import dagger.BindsInstance
import dagger.hilt.DefineComponent
import io.github.lib_automata.ScreenshotService
import io.github.fate_grand_automata.runner.ScriptRunPreferences

@DefineComponent.Builder
interface ScriptComponentBuilder {
    fun screenshotService(@BindsInstance screenshotService: ScreenshotService): ScriptComponentBuilder
    fun runPreferences(@BindsInstance preferences: ScriptRunPreferences): ScriptComponentBuilder
    fun build(): ScriptComponent
}
