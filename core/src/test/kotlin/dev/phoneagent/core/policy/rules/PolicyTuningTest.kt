package dev.phoneagent.core.policy.rules

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Thresholds are checked when the configuration is built (policy-rules.md §8), and every one of
 * them is named for the run's record (`policy/rules`).
 */
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

    /** A valid value of every field, none of them its default. */
    private val changed =
        PolicyTuning(
            labelMax = 13,
            contextMax = 41,
            shortText = 21,
            totalTextMax = 31,
            labelRead = 121,
            titleBand = 151,
            clearNear = 81,
            rowSwipeMin = 101,
            rowMinWidth = 601,
            carouselMax = 401,
            maxElements = 5_001,
            maxScreenText = 100_001,
            maxDepth = 101,
            totalsRead = 9,
        )

    @Test
    fun `every field is named with its value, in declaration order`() {
        assertEquals(
            mapOf(
                "labelMax" to 13,
                "contextMax" to 41,
                "shortText" to 21,
                "totalTextMax" to 31,
                "labelRead" to 121,
                "titleBand" to 151,
                "clearNear" to 81,
                "rowSwipeMin" to 101,
                "rowMinWidth" to 601,
                "carouselMax" to 401,
                "maxElements" to 5_001,
                "maxScreenText" to 100_001,
                "maxDepth" to 101,
                "totalsRead" to 9,
            ).toList(),
            changed.byName().toList(),
        )
        // A field added to the class but not to the record would make the record incomplete: the
        // data class's own text lists every field in declaration order.
        val declared = Regex("""(\w+)=""").findAll(changed.toString()).map { it.groupValues[1] }.toList()
        assertEquals(declared, changed.byName().keys.toList())
        assertEquals(lengths.keys + distances.keys + limits.keys, PolicyTuning().byName().keys)
    }
}
