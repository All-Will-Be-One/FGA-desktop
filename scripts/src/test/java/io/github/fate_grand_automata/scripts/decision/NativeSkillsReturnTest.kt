package io.github.fate_grand_automata.scripts.decision

import io.github.fate_grand_automata.scripts.IFgoAutomataApi
import io.github.fate_grand_automata.scripts.Images
import io.github.lib_automata.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class NativeSkillsReturnTest {
    private class Fixture {
        val api = mockk<IFgoAutomataApi>(relaxed = true)
        val back = Location(2437, 1110)
        val region = Region(10, 10, 200, 88)
        val attack = mockk<Pattern>(relaxed = true)
        init {
            every { api.locations.attack.backClick } returns back
            every { api.locations.battle.screenCheckRegion } returns region
            every { api.images[Images.BattleScreen, any()] } returns attack
        }
        fun verifySingleBackAndBoundedWait() {
            verify(exactly = 1) { with(api) { back.click(1) } }
            verify(exactly = 1) { with(api) { region.exists(attack, 5.seconds, null) } }
            verify(exactly = 0) { api.useSameSnapIn<Unit>(any()) }
        }
    }

    @Test
    fun `real native matcher polls a fresh frame after a transient miss without another Back`() {
        val f = Fixture()
        val screenshots = mockk<ScreenshotManager>()
        val pattern = mockk<Pattern>(relaxed = true)
        val transform = mockk<Transformer>()
        val platform = mockk<PlatformImpl>(relaxed = true)
        every { screenshots.getScreenshot() } returns pattern
        every { pattern.crop(any()) } returns pattern
        every { transform.toImage(f.region) } returns f.region
        every { transform.fromImage(any()) } answers { firstArg() }
        every { platform.prefs.minSimilarity } returns .8
        var samples = 0
        every { pattern.findMatches(f.attack, .8) } answers {
            samples++
            if (samples == 1) emptySequence() else sequenceOf(Match(f.region, .95))
        }
        val wait = object : Waiter { override fun invoke(duration: Duration, applyMultiplier: Boolean) {} }
        val matcher = RealImageMatcher(ExitManager(), screenshots, platform, wait, mockk(relaxed = true), transform)
        every { with(f.api) { f.region.exists(f.attack, 5.seconds, null) } } answers {
            matcher.exists(f.region, f.attack, 5.seconds, null)
        }
        f.api.returnToExternalSkills()
        assertEquals(2, samples)
        f.verifySingleBackAndBoundedWait()
    }

    @Test
    fun `native wait timeout reports the return checkpoint without repeating a gesture`() {
        val f = Fixture()
        every { with(f.api) { f.region.exists(f.attack, 5.seconds, null) } } returns false
        assertEquals("NativeReturnToSkillsTimeout", assertFailsWith<NativeDecisionException> {
            f.api.returnToExternalSkills()
        }.message)
        f.verifySingleBackAndBoundedWait()
    }

    @Test
    fun `cancellation from native waiting propagates without retry or success`() {
        val f = Fixture()
        every { with(f.api) { f.region.exists(f.attack, 5.seconds, null) } } throws ScriptAbortException()
        assertFailsWith<ScriptAbortException> { f.api.returnToExternalSkills() }
        f.verifySingleBackAndBoundedWait()
    }
}
