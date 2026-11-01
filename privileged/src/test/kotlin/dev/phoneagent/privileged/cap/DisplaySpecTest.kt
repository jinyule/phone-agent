package dev.phoneagent.privileged.cap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaySpecTest {
    private fun has(
        sdkInt: Int,
        flag: Int,
    ) = DisplaySpec.flagsFor(sdkInt) and flag != 0

    @Test
    fun everyVersionGetsTheBaseFlags() {
        listOf(30, 33, 34, 36).forEach { sdk ->
            assertTrue("API $sdk is not public", has(sdk, DisplaySpec.PUBLIC))
            assertTrue("API $sdk mirrors content", has(sdk, DisplaySpec.OWN_CONTENT_ONLY))
            assertTrue("API $sdk takes no touches", has(sdk, DisplaySpec.SUPPORTS_TOUCH))
            assertTrue("API $sdk keeps content on removal", has(sdk, DisplaySpec.DESTROY_CONTENT_ON_REMOVAL))
        }
    }

    @Test
    fun api30GetsOnlyTheBaseFlags() {
        val expected =
            DisplaySpec.PUBLIC or DisplaySpec.OWN_CONTENT_ONLY or DisplaySpec.SUPPORTS_TOUCH or
                DisplaySpec.ROTATES_WITH_CONTENT or DisplaySpec.DESTROY_CONTENT_ON_REMOVAL
        assertEquals(expected, DisplaySpec.flagsFor(30))
    }

    @Test
    fun trustedAndUnlockedArriveWithApi33() {
        listOf(DisplaySpec.TRUSTED, DisplaySpec.OWN_DISPLAY_GROUP, DisplaySpec.ALWAYS_UNLOCKED).forEach { flag ->
            assertFalse(has(32, flag))
            assertTrue(has(33, flag))
        }
    }

    @Test
    fun theFocusFlagsArriveTogetherWithApi34() {
        assertFalse("OWN_FOCUS before API 34", has(33, DisplaySpec.OWN_FOCUS))
        assertFalse("STEAL_TOP_FOCUS_DISABLED before API 34", has(33, DisplaySpec.STEAL_TOP_FOCUS_DISABLED))
        assertTrue(has(34, DisplaySpec.OWN_FOCUS))
        assertTrue(has(34, DisplaySpec.STEAL_TOP_FOCUS_DISABLED))
        // STEAL_TOP_FOCUS_DISABLED only works with OWN_FOCUS and TRUSTED.
        assertTrue(has(34, DisplaySpec.TRUSTED))
    }

    @Test
    fun keepingTheUsersFocusNeedsApi34() {
        assertFalse(DisplaySpec.keepsUserFocus(33))
        assertTrue(DisplaySpec.keepsUserFocus(34))
    }

    @Test
    fun flagsOnlyGrowWithTheApiLevel() {
        (30..36).zipWithNext().forEach { (lower, higher) ->
            val before = DisplaySpec.flagsFor(lower)
            val after = DisplaySpec.flagsFor(higher)
            assertEquals("API $higher dropped a flag API $lower had", before, before and after)
        }
    }

    @Test
    fun displaySizeAndDensityAreBounded() {
        assertNull(DisplaySpec.problem(1080, 2340, 440))
        assertNull(DisplaySpec.problem(DisplaySpec.MIN_EDGE, DisplaySpec.MAX_EDGE, 60))
        assertNotNull(DisplaySpec.problem(0, 2340, 440))
        assertNotNull(DisplaySpec.problem(1080, DisplaySpec.MAX_EDGE + 1, 440))
        assertNotNull(DisplaySpec.problem(1080, 2340, 0))
        assertNotNull(DisplaySpec.problem(1080, 2340, 5_000))
    }

    @Test
    fun theProblemNamesWhatIsWrong() {
        assertTrue(DisplaySpec.problem(10, 2340, 440).orEmpty().contains("10x2340"))
        assertTrue(DisplaySpec.problem(1080, 2340, 1).orEmpty().contains("density"))
    }
}
