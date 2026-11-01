package dev.phoneagent.core.session

import dev.phoneagent.core.policy.PolicyContext
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * B3: every input of PolicyEngine is derivable from the session log (phase1 §3.6, addendum §8).
 * Each `PolicyContext` property names the log fields it is computed from; a new property without
 * a source, or a source that no longer exists, fails here.
 */
class PolicyContextSourcesTest {
    /** `header.<field>` or `<event type>.<field>`. */
    private val sources: Map<String, List<String>> =
        mapOf(
            "taskId" to listOf("header.taskId"),
            "level" to listOf("task/start.level", "task/level-changed.level"),
            "unattended" to listOf("task/start.unattended"),
            // The snapshot the call was planned on; for the re-check after approval, the fresh snapshot S'
            // (addendum §14) is the screen/snapshot recorded after approval/decided.
            "screen" to listOf("screen/snapshot.snapshot", "screen/snapshot.callId"),
            // intent.pkg of every executed non-READ call: dispatched calls and their resolved intents.
            "operatedPackages" to listOf("tool/call.call", "policy/verdict.callId", "policy/verdict.intent"),
            // card.pkg of every APPROVED card with reason OUTWARD.
            "outwardConfirmedPackages" to listOf("approval/asked.card", "approval/decided.result"),
            // The card just approved for this call, during the re-check.
            "approved" to listOf("approval/asked.card", "approval/asked.promptId", "approval/decided.result"),
        )

    private val properties =
        PolicyContext::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    @Test
    fun `every policy context property has a source in the log`() {
        assertEquals(properties, sources.keys, "PolicyContext properties without a log source, or stale rows")
    }

    @Test
    fun `every source names an existing field of the header or an event`() {
        val events = serializer<SessionEvent>().descriptor.getElementDescriptor(1)
        val missing =
            sources.values.flatten().distinct().filterNot { source ->
                val type = source.substringBeforeLast('.')
                val field = source.substringAfterLast('.')
                val descriptor: SerialDescriptor? =
                    if (type == "header") {
                        serializer<SessionHeader>().descriptor
                    } else {
                        events.elementNames
                            .indexOf(type)
                            .takeIf { it >= 0 }
                            ?.let(events::getElementDescriptor)
                    }
                descriptor != null && field in descriptor.elementNames
            }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the task settings are not ignorable, so no reader can skip them`() {
        listOf(
            "task/start",
            "task/level-changed",
        ).forEach { assertEquals(false, SessionEventTypes.isIgnorable(it), it) }
    }
}
