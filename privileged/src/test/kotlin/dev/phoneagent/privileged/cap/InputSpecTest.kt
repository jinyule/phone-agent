package dev.phoneagent.privileged.cap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressKeysTest {
    @Test
    fun navigationAndEditingKeysAreAllowed() {
        listOf("KEYCODE_BACK", "KEYCODE_ENTER", "KEYCODE_DEL", "KEYCODE_DPAD_DOWN", "KEYCODE_TAB").forEach { name ->
            val code = checkNotNull(PressKeys.BY_NAME[name]) { "$name is not listed" }
            assertTrue("$name is refused", PressKeys.isAllowed(code))
        }
    }

    @Test
    fun wholePhoneKeysAreRefused() {
        // KEYCODE_HOME 3, POWER 26, VOLUME_UP 24, VOLUME_DOWN 25, APP_SWITCH 187, SEARCH 84,
        // ASSIST 219, CAMERA 27, MEDIA_PLAY_PAUSE 85, SLEEP 223, WAKEUP 224.
        listOf(3, 26, 24, 25, 187, 84, 219, 27, 85, 223, 224).forEach { code ->
            assertFalse("key $code is allowed", PressKeys.isAllowed(code))
        }
    }

    @Test
    fun unknownCodesAreRefused() {
        assertFalse(PressKeys.isAllowed(0))
        assertFalse(PressKeys.isAllowed(-1))
        assertFalse(PressKeys.isAllowed(9_999))
    }
}

class StrokeSpecTest {
    private val width = 1080
    private val height = 2340

    private fun problem(
        xs: IntArray,
        ys: IntArray,
        times: LongArray,
    ) = StrokeSpec.problem(xs, ys, times, width, height)

    @Test
    fun aTapIsTwoPointsAtTheSamePlace() {
        assertNull(problem(intArrayOf(540, 540), intArrayOf(1_200, 1_200), longArrayOf(0, 50)))
    }

    @Test
    fun aSwipeMayHoldStill() {
        assertNull(problem(intArrayOf(540, 540, 540), intArrayOf(1_800, 1_500, 900), longArrayOf(0, 100, 100)))
    }

    @Test
    fun aStrokeNeedsADownAndAnUp() {
        assertTrue(problem(intArrayOf(1), intArrayOf(1), longArrayOf(0)).orEmpty().contains("at least 2 points"))
    }

    @Test
    fun theArraysMustBeParallel() {
        assertTrue(problem(intArrayOf(1, 2), intArrayOf(1), longArrayOf(0, 10)).orEmpty().contains("equal length"))
        assertTrue(problem(intArrayOf(1, 2), intArrayOf(1, 2), longArrayOf(0)).orEmpty().contains("equal length"))
    }

    @Test
    fun timesStartAtZeroAndNeverGoBackwards() {
        assertTrue(problem(intArrayOf(1, 2), intArrayOf(1, 2), longArrayOf(5, 10)).orEmpty().contains("start at 0"))
        val back = problem(intArrayOf(1, 2, 3), intArrayOf(1, 2, 3), longArrayOf(0, 20, 10))
        assertTrue(back.orEmpty().contains("backwards"))
        assertTrue("the reason names the point", back.orEmpty().contains("point 2"))
    }

    @Test
    fun aStrokeIsBounded() {
        val n = StrokeSpec.MAX_POINTS + 1
        val tooMany = problem(IntArray(n) { 1 }, IntArray(n) { 1 }, LongArray(n) { it.toLong() })
        assertTrue(tooMany.orEmpty().contains("at most ${StrokeSpec.MAX_POINTS} points"))
        val tooLong = problem(intArrayOf(1, 2), intArrayOf(1, 2), longArrayOf(0, StrokeSpec.MAX_MS + 1))
        assertTrue(tooLong.orEmpty().contains("at most ${StrokeSpec.MAX_MS} ms"))
    }

    @Test
    fun everyPointStaysOnTheDisplay() {
        val outside = problem(intArrayOf(540, width), intArrayOf(10, 10), longArrayOf(0, 10))
        assertTrue(outside.orEmpty().contains("outside the ${width}x$height display"))
        assertTrue(problem(intArrayOf(0, 0), intArrayOf(0, height), longArrayOf(0, 10)).orEmpty().contains("outside"))
        assertTrue(problem(intArrayOf(-1, 0), intArrayOf(0, 0), longArrayOf(0, 10)).orEmpty().contains("outside"))
        assertNull(problem(intArrayOf(0, width - 1), intArrayOf(0, height - 1), longArrayOf(0, 10)))
    }
}
