package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.Interceptors
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.ScriptedScreen
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The loop of a replay runs the loop's default interceptors at every interception point, item by
 * item, so a chain the default gains (as `requestError` gained ReasoningDropRecovery) reaches
 * replay too.
 */
class ReplayHarnessTest {
    /** Each interception point of [interceptors], by its property, as the classes of its chain in order. */
    private fun chains(interceptors: Interceptors): Map<String, List<Class<*>?>> =
        Interceptors::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .associate { field ->
                field.isAccessible = true
                field.name to (field.get(interceptors) as List<*>).map { it?.javaClass }
            }

    @Test
    fun `every interception point runs the default chain`() =
        runTest {
            val harness =
                ReplayHarness(this, ReplaySamples.scenario, FakeChatModel(ReplaySamples.info)) { _, _ ->
                    ScriptedScreen(Screens.CHAT)
                }
            val defaults = chains(Interceptors())
            assertTrue(
                defaults.keys.containsAll(listOf("preStep", "requestError", "toolPreExecute", "turnStopping")),
                "${defaults.keys}",
            )
            assertTrue(defaults.getValue("requestError").isNotEmpty(), "$defaults")
            assertEquals(defaults, chains(harness.setup.interceptors))
        }
}
