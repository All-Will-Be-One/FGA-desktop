package io.github.fate_grand_automata.scripts.locations

import io.github.fate_grand_automata.scripts.models.Skill
import io.github.lib_automata.Location
import io.github.lib_automata.Region

/** Shared upstream master coordinates with an explicitly observed menu anchor. */
object MasterAnchoredLocations {
    fun searchRegion(transforms: IScriptAreaTransforms) = with(transforms) { Region(-400,360,400,80).xFromRight() }
    fun locate(skill: Skill.Master, menuAnchor: Location) = when (skill) {
        Skill.Master.A -> -740
        Skill.Master.B -> -560
        Skill.Master.C -> -400
    }.let { x -> Location(x + 178, 620) + menuAnchor.copy(y = 0) }
    fun open(menuAnchor: Location) = Location(0, 640) + menuAnchor.copy(y = 0)
}
