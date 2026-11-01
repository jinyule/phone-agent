package dev.phoneagent.privileged.cap

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReleaseTest {
    @Test
    fun releaseEachGoesOnPastAFailureAndReturnsTheFailedItemsInOrder() {
        val released = mutableListOf<Int>()
        val kept =
            releaseEach(listOf(1, 2, 3, 4), { "display $it" }) { id ->
                check(id % 2 == 1) { "display $id is busy" }
                released += id
            }
        assertEquals("a failure does not stop the others", listOf(1, 3), released)
        assertEquals(listOf(2, 4), kept)
    }

    @Test
    fun releaseStepsRunsEveryStepAndNamesTheOneThatFailed() {
        val ran = mutableListOf<String>()
        val summary =
            releaseSteps(
                "mutes restored" to {
                    ran += "mutes"
                    2
                },
                "displays released" to {
                    ran += "displays"
                    error("system_server went away")
                },
                "tasks forgotten" to {
                    ran += "tasks"
                    3
                },
            )
        assertEquals("a failed step does not stop the later ones", listOf("mutes", "displays", "tasks"), ran)
        assertEquals(
            "mutes restored 2, displays released failed (java.lang.IllegalStateException: system_server went away), " +
                "tasks forgotten 3",
            summary,
        )
    }
}
