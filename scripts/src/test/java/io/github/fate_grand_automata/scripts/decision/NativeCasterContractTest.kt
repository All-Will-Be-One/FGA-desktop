package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.IImageLoader
import io.github.fate_grand_automata.scripts.Images
import io.github.fate_grand_automata.scripts.locations.BattleScreenLocations
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.locations.MasterLocations
import io.github.fate_grand_automata.scripts.models.*
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.modules.Caster
import io.github.fate_grand_automata.scripts.modules.ServantTracker
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.Pattern
import io.github.lib_automata.Location
import io.github.lib_automata.Region
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Replays upstream API calls; these tests do not assert game effect or new dialog recognition. */
class NativeCasterContractTest {
    private class Fixture(confirm: Boolean = false) {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val images = mockk<IImageLoader>()
        val locations = mockk<Locations>(relaxed = true)
        val battle = mockk<BattleScreenLocations>(relaxed = true)
        val master = mockk<MasterLocations>(relaxed = true)
        val targetLocation = mockk<Location>()
        val tracker = mockk<ServantTracker>(relaxed = true)
        val prefs = mockk<IPreferences>(relaxed = true)
        val attack = mockk<Pattern>()
        val confirmation = mockk<Pattern>()
        val caster: Caster
        init {
            every { api.locations } returns locations
            every { locations.battle } returns battle
            every { battle.master } returns master
            every { battle.locate(any<ServantTarget>()) } returns targetLocation
            every { api.images } returns images
            every { api.prefs } returns prefs
            every { prefs.skillDelay } returns Duration.ZERO
            every { images[Images.BattleScreen, any()] } returns attack
            every { images[Images.SkillUse, any()] } returns confirmation
            every { with(api) { any<Region>().exists(confirmation, any(), any()) } } returns confirm
            // False vanish deliberately models a fast skill which upstream still delivers.
            every { with(api) { any<Region>().waitVanish(attack, any(), any()) } } returns false
            every { with(api) { any<Region>().exists(attack, any(), any()) } } returns true
            every { tracker.deployed } returns mapOf(FieldSlot.A to TeamSlot.A, FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.C)
            caster = Caster(api, BattleState(), tracker)
        }
    }

    @Test
    fun `explicit targeted skill keeps upstream confirmation target and bounded waits`() {
        val f = Fixture(confirm = true)
        f.caster.castServantSkill(Skill.Servant.A2, nativeServantTarget(3))
        verifyOrder {
            f.battle.locate(Skill.Servant.A2)
            with(f.api) { f.battle.skillOkClick.click(1) }
            f.battle.locate(ServantTarget.C)
            with(f.api) { f.targetLocation.click(1) }
            with(f.api) { f.battle.extraInfoWindowCloseClick.click(1) }
            with(f.api) { f.battle.screenCheckRegion.waitVanish(f.attack, 2.seconds, null) }
            with(f.api) { f.battle.screenCheckRegion.exists(f.attack, 5.seconds, null) }
        }
        verify(exactly = 0) { f.battle.locate(ServantTarget.A) }
        verify(exactly = 0) { f.battle.locate(ServantTarget.B) }
    }

    @Test
    fun `external no-dialog profile creates no target click for servant or master skill`() {
        val f = Fixture()
        f.caster.castServantSkill(Skill.Servant.B1, nativeServantTarget(null))
        f.caster.castMasterSkill(Skill.Master.A, nativeServantTarget(null))
        verify(exactly = 0) { f.battle.locate(any<ServantTarget>()) }
        verify(exactly = 0) { with(f.api) { f.targetLocation.click(1) } }
        verify(exactly = 1) { f.battle.locate(Skill.Servant.B1) }
        verify(exactly = 1) { f.master.locate(Skill.Master.A) }
    }

    @Test
    fun `valid explicit logical target uses upstream two and single servant UI remapping`() {
        val two = Fixture()
        every { two.tracker.deployed } returns mapOf(FieldSlot.B to TeamSlot.B, FieldSlot.C to TeamSlot.C)
        two.caster.castServantSkill(Skill.Servant.B1, nativeServantTarget(2))
        verify(exactly = 1) { two.battle.locate(ServantTarget.Left) }
        verify(exactly = 1) { with(two.api) { two.targetLocation.click(1) } }
        val one = Fixture()
        every { one.tracker.deployed } returns mapOf(FieldSlot.C to TeamSlot.C)
        one.caster.castServantSkill(Skill.Servant.C1, nativeServantTarget(3))
        verify(exactly = 1) { one.battle.locate(ServantTarget.B) }
        verify(exactly = 1) { with(one.api) { one.targetLocation.click(1) } }
    }

    @Test
    fun `explicit order change uses the declared native positions and tracking`() {
        val f = Fixture()
        val action = AutoSkillAction.OrderChange(OrderChangeMember.Starting.B, OrderChangeMember.Sub.C)
        f.caster.orderChange(action)
        verifyOrder {
            f.master.locate(Skill.Master.C)
            f.battle.locate(OrderChangeMember.Starting.B)
            f.battle.locate(OrderChangeMember.Sub.C)
            with(f.api) { f.battle.orderChangeOkClick.click(1) }
            with(f.api) { f.battle.screenCheckRegion.exists(f.attack, 15.seconds, null) }
            f.tracker.orderChanged(OrderChangeMember.Starting.B, OrderChangeMember.Sub.C)
        }
    }
}
