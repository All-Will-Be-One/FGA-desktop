package io.github.fate_grand_automata.scripts.locations

import io.github.fate_grand_automata.scripts.IImageLoader
import io.github.fate_grand_automata.scripts.IScriptMessages
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.ScriptLog
import io.github.fate_grand_automata.scripts.models.Skill
import io.github.lib_automata.AutomataApi
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.github.lib_automata.dagger.ScriptScope
import javax.inject.Inject

@ScriptScope
class MasterLocations @Inject constructor(
    scriptAreaTransforms: IScriptAreaTransforms,
    private val images: IImageLoader,
    private val automataApi: AutomataApi,
    private val messages: IScriptMessages,
) : IScriptAreaTransforms by scriptAreaTransforms {
    // Master Skills and Stage counter are right-aligned differently,
    // so we use locations relative to a matched location
    private val masterOffsetNewUI: Location by lazy {
        automataApi.run {
            MasterAnchoredLocations.searchRegion(this@MasterLocations)
                .find(images[Images.BattleMenu])
                ?.region
                ?.center
                ?.copy(y = 0)
                ?: Location(-298, 0).xFromRight().also {
                    messages.log(ScriptLog.DefaultMasterOffset)
                }
        }
    }

    fun locate(skill: Skill.Master) = MasterAnchoredLocations.locate(skill, masterOffsetNewUI)

    val stageCountRegion
        get() = Region(if (isWide) -571 else -638, 23, 33, 53) + masterOffsetNewUI

    val masterSkillOpenClick
        get() = MasterAnchoredLocations.open(masterOffsetNewUI)

    val commandSpellSearchRegion = when (isWide) {
        true -> Region(-590, 180, 190, 120).xFromRight()
        false -> Region(-520, 210, 190, 120).xFromRight()
    }

    val cancelCommandSpellRegion = Region(-202, 764, 95, 174).xFromCenter()

    fun locate(skill: Skill.CommandSpell) = when (skill) {
        Skill.CommandSpell.NpCharge -> 700
    }.let { y ->
        Location(0, y).xFromCenter()
    }
}