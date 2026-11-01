package dev.phoneagent.app.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckStepsTest {
    private val attemptsBeforeReady = 3

    @Test
    fun allPassingStepsEndInPass() {
        val steps = SelfCheckSteps()
        steps.note("helper pid 1234")
        assertTrue(steps.check("createDisplay", passed = true, detail = "id 7"))
        assertEquals(
            listOf("helper pid 1234", "createDisplay: ok id 7", "PASS"),
            steps.lines(),
        )
    }

    @Test
    fun theVerdictNamesEveryFailedStep() {
        val steps = SelfCheckSteps()
        assertTrue(steps.check("probe", passed = true))
        assertFalse(steps.check("injectKey", passed = false, detail = "refused"))
        assertFalse(steps.fail("frame", "no frame within 8000 ms"))
        val lines = steps.lines()
        assertEquals("FAIL: injectKey, frame", lines.last())
        assertTrue(lines.contains("probe: ok"))
        assertTrue(lines.contains("injectKey: FAILED refused"))
        assertTrue(lines.contains("frame: FAILED no frame within 8000 ms"))
    }

    @Test
    fun anEmptyCheckRunStillHasAVerdict() {
        assertEquals(listOf("PASS"), SelfCheckSteps().lines())
    }

    @Test
    fun pollingReturnsAsSoonAsTheValueArrives() {
        var attempts = 0
        val value =
            pollUntil(timeoutMs = 1_000, stepMs = 1) {
                attempts++
                "ready".takeIf { attempts >= attemptsBeforeReady }
            }
        assertEquals("ready", value)
        assertEquals(attemptsBeforeReady, attempts)
    }

    @Test
    fun pollingGivesUpAndTriesOnceMore() {
        var attempts = 0
        val value =
            pollUntil<String>(timeoutMs = 5, stepMs = 1) {
                attempts++
                null
            }
        assertEquals(null, value)
        assertTrue("it polled at least once and made a final attempt", attempts >= 2)
    }
}
