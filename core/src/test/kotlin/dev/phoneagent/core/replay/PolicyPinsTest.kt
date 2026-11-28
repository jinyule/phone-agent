package dev.phoneagent.core.replay

import dev.phoneagent.core.policy.rules.PolicyTuning
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier

class PolicyPinsTest {
    private val defaults = PolicyPins.values(PolicyTuning())

    @Test
    fun `every field of PolicyTuning has a name, in declaration order`() {
        val fields =
            PolicyTuning::class.java.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .map { it.name }
        assertEquals(fields, PolicyPins.NAMES, "a new PolicyTuning field needs its entry in PolicyPins")
    }

    @Test
    fun `each name reads and sets its own field`() {
        // Collected, then failed at once: a cross-wired pair shows as two names.
        val wrong =
            PolicyPins.NAMES.filter { name ->
                val value = defaults.getValue(name) + 1
                PolicyPins.values(PolicyPins.tuning(mapOf(name to value))) != defaults + (name to value)
            }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `no fields set is the default tuning`() {
        assertEquals(PolicyTuning(), PolicyPins.tuning(emptyMap()))
    }

    @Test
    fun `a name that is no field fails and lists the fields`() {
        val message = assertThrows<IllegalArgumentException> { PolicyPins.tuning(mapOf("labelMx" to 3)) }.message
        assertTrue(message.orEmpty().contains("[labelMx]") && message.orEmpty().contains("labelMax"), message)
    }

    @Test
    fun `a value the tuning refuses fails`() {
        assertThrows<IllegalArgumentException> { PolicyPins.tuning(mapOf("labelMax" to 0)) }
    }

    @Test
    fun `the current pin is the rules version and every field`() {
        val pin = PolicyPins.current(PolicyTuning(maxDepth = 7))
        assertEquals(PolicyTuning.RULES_VERSION, pin.rulesVersion)
        assertEquals(defaults + ("maxDepth" to 7), pin.tuning)
    }

    @Test
    fun `equal pins have no differences`() {
        val pin = PolicyPins.current(PolicyTuning())
        assertEquals(emptyList<String>(), PolicyPins.differences(pin, pin.copy()))
    }

    @Test
    fun `another rules version is a difference`() {
        val pin = PolicyPins.current(PolicyTuning())
        val lines = PolicyPins.differences(pin.copy(rulesVersion = pin.rulesVersion - 1), pin)
        assertEquals(1, lines.size, "$lines")
        assertTrue(lines.single().startsWith("policy.rulesVersion: the fixture was judged by rules"), "$lines")
    }

    @Test
    fun `another value of one field is a difference naming it`() {
        val pin = PolicyPins.current(PolicyTuning())
        val changed = pin.copy(tuning = pin.tuning + ("titleBand" to 140))
        assertEquals(
            listOf("policy.tuning.titleBand: the fixture was judged with 140, replay runs with 150"),
            PolicyPins.differences(changed, pin),
        )
    }

    @Test
    fun `a field the fixture lacks is a difference`() {
        val pin = PolicyPins.current(PolicyTuning())
        assertEquals(
            listOf("policy.tuning.totalsRead: the fixture was judged with null, replay runs with 8"),
            PolicyPins.differences(pin.copy(tuning = pin.tuning - "totalsRead"), pin),
        )
    }
}
