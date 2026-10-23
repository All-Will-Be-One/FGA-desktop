package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.entrypoints.AutoBattle
import io.github.fate_grand_automata.scripts.models.BoostItem
import io.github.fate_grand_automata.scripts.models.battle.BattleState
import io.github.fate_grand_automata.scripts.locations.Locations
import io.github.fate_grand_automata.scripts.prefs.IPreferences
import io.github.lib_automata.*
import io.mockk.*
import kotlin.test.Test
import io.github.fate_grand_automata.scripts.modules.PartySelection
import kotlin.time.Duration.Companion.seconds

class BoostSkipContractTest {
    @Test fun `native boost handler only delivers existing skip location`() {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val prefs = mockk<IPreferences>(relaxed = true)
        val locations = mockk<Locations>(relaxed = true)
        val skip = Location(12, 34)
        every { api.prefs } returns prefs
        every { api.locations } returns locations
        every { prefs.boostItemSelectionMode } returns 0
        every { locations.locate(BoostItem.Enabled.Skip) } returns skip
        val battle = AutoBattle(mockk(relaxed = true), api, BattleState(), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        AutoBattle::class.java.getDeclaredMethod("useBoostItem").apply { isAccessible = true }.invoke(battle)
        verify(exactly = 1) { with(api) { skip.click(1) } }
        verify(exactly = 0) { locations.locate(BoostItem.Enabled.BoostItem1) }
        verify(exactly = 0) { locations.locate(BoostItem.Enabled.BoostItem2) }
        verify(exactly = 0) { locations.locate(BoostItem.Enabled.BoostItem3) }
    }
    @Test fun `upstream quest start includes unconditional skip click without choosing resources`() {
        val api=mockk<IFgoAutomataApi>(relaxed=true)
        val prefs=mockk<IPreferences>(relaxed=true)
        val locations=mockk<Locations>(relaxed=true)
        val party=mockk<PartySelection>(relaxed=true)
        val start=Location(55,66); val skip=Location(12,34)
        every { api.prefs } returns prefs; every { api.locations } returns locations
        every { prefs.boostItemSelectionMode } returns 0
        every { locations.menuStartQuestClick } returns start
        every { locations.locate(BoostItem.Enabled.Skip) } returns skip
        val battle=AutoBattle(mockk(relaxed=true),api,BattleState(),mockk(relaxed=true),
            mockk(relaxed=true),mockk(relaxed=true),party,mockk(relaxed=true),
            mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
        AutoBattle::class.java.getDeclaredMethod("startQuest").apply { isAccessible=true }.invoke(battle)
        verifyOrder { party.selectParty(); with(api) { start.click(1); 2.seconds.wait(true); skip.click(1) } }
        verify(exactly=0) { locations.locate(BoostItem.Enabled.BoostItem1) }
        verify(exactly=0) { locations.locate(BoostItem.Enabled.BoostItem2) }
        verify(exactly=0) { locations.locate(BoostItem.Enabled.BoostItem3) }
    }
}
