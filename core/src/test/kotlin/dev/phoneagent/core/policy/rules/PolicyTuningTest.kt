package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Thresholds are checked when the configuration is built (policy-rules.md §8). */
class PolicyTuningTest {
    private val lengths: Map<String, (Int) -> PolicyTuning> =
        mapOf(
            "labelMax" to { v -> PolicyTuning(labelMax = v) },
            "contextMax" to { v -> PolicyTuning(contextMax = v) },
            "shortText" to { v -> PolicyTuning(shortText = v) },
            "totalTextMax" to { v -> PolicyTuning(totalTextMax = v) },
            "labelRead" to { v -> PolicyTuning(labelRead = v) },
        )

    private val limits: Map<String, (Int) -> PolicyTuning> =
        mapOf(
            "maxElements" to { v -> PolicyTuning(maxElements = v) },
            "maxScreenText" to { v -> PolicyTuning(maxScreenText = v) },
            "maxDepth" to { v -> PolicyTuning(maxDepth = v) },
            "totalsRead" to { v -> PolicyTuning(totalsRead = v) },
        )

    private val distances: Map<String, (Int) -> PolicyTuning> =
        mapOf(
            "titleBand" to { v -> PolicyTuning(titleBand = v) },
            "clearNear" to { v -> PolicyTuning(clearNear = v) },
            "rowSwipeMin" to { v -> PolicyTuning(rowSwipeMin = v) },
            "rowMinWidth" to { v -> PolicyTuning(rowMinWidth = v) },
            "carouselMax" to { v -> PolicyTuning(carouselMax = v) },
        )

    @Test
    fun `the defaults are valid`() {
        assertEquals(12, PolicyTuning().labelMax)
        assertEquals(100, PolicyTuning().rowSwipeMin)
        assertEquals(
            listOf(120, 5_000, 100_000, 100, 8),
            PolicyTuning().let {
                listOf(it.labelRead, it.maxElements, it.maxScreenText, it.maxDepth, it.totalsRead)
            },
        )
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = [0, -1, 1001])
    fun `a length outside 1 to 1000 fails, naming the field`(value: Int) {
        for ((name, build) in lengths) {
            val error = assertThrows<IllegalArgumentException>(name) { build(value) }
            assertEquals("PolicyTuning.$name must be in 1..1000, got $value", error.message)
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = [-1, 1000])
    fun `a distance outside the screen coordinates fails, naming the field`(value: Int) {
        for ((name, build) in distances) {
            val error = assertThrows<IllegalArgumentException>(name) { build(value) }
            assertEquals("PolicyTuning.$name must be in 0..999, got $value", error.message)
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(ints = [0, -1, 10_000_001])
    fun `a limit outside 1 to 10000000 fails, naming the field`(value: Int) {
        for ((name, build) in limits) {
            val error = assertThrows<IllegalArgumentException>(name) { build(value) }
            assertEquals("PolicyTuning.$name must be in 1..10000000, got $value", error.message)
        }
    }

    @Test
    fun `the ends of each range are valid`() {
        for (build in lengths.values) {
            build(1)
            build(1000)
        }
        for (build in limits.values) {
            build(1)
            build(10_000_000)
        }
        for (build in distances.values) {
            build(0)
            build(999)
        }
    }
}
