package dev.phoneagent.core.replay

import dev.phoneagent.core.agent.AgentSetup
import dev.phoneagent.core.agent.Interceptors
import dev.phoneagent.core.assembly.ProductAssembly
import dev.phoneagent.core.assembly.ProductConfig
import dev.phoneagent.core.assembly.assemble
import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.ScriptedScreen
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * The loop of a replay is the product's agent: [assemble] with the scenario's packages, labels and
 * tuning, part by part what the app runs (ADR 2026-11-13-product-assembly), with the scenario's
 * output cap and reasoning level over the request plan and vision taken from the model.
 */
class ReplayHarnessTest {
    private val tuned =
        ReplaySamples.scenario.copy(
            assembly =
                Assembly(
                    ownPackages = setOf("dev.phoneagent", "dev.phoneagent.debug"),
                    ownLabels = setOf("PhoneAgent", "手机助理"),
                    tuning = mapOf("labelMax" to 30),
                ),
        )

    private fun TestScope.harness(
        scenario: Scenario,
        info: ModelInfo = scenario.model,
    ): ReplayHarness = ReplayHarness(this, scenario, FakeChatModel(info)) { _, _ -> ScriptedScreen(Screens.CHAT) }

    /** The product's assembly for [scenario], built apart from the harness around [model]. */
    private fun product(
        scenario: Scenario,
        model: ChatModel,
    ): ProductAssembly =
        assemble(model, ReplayHarness.config(scenario.assembly, PolicyPins.tuning(scenario.assembly.tuning)))

    /** Each interception point of [interceptors], by its property, as the classes of its chain in order. */
    private fun chains(interceptors: Interceptors): Map<String, List<Class<*>?>> =
        Interceptors::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .associate { field ->
                field.isAccessible = true
                field.name to (field.get(interceptors) as List<*>).map { it?.javaClass }
            }

    private fun assertProductParts(
        product: ProductAssembly,
        setup: AgentSetup,
    ) {
        assertEquals(product.plan.system, setup.plan.system)
        assertEquals(product.plan.tools, setup.plan.tools)
        assertEquals(product.plan.cache, setup.plan.cache)
        assertEquals(product.registry.manifest, setup.registry.manifest)
        assertEquals(product.policyRules, setup.rules)
        assertEquals(product.loop, setup.config)
        assertEquals(product.policy.javaClass, setup.policy.javaClass)
        assertEquals(product.intents.javaClass, setup.intents.javaClass)
        assertEquals(chains(product.interceptors), chains(setup.interceptors))
    }

    @Test
    fun `the loop is the product's agent with the scenario's names and tuning`() =
        runTest {
            val harness = harness(tuned)
            val setup = harness.setup
            assertProductParts(product(tuned, harness.tape), setup)
            assertEquals(JsonPrimitive(30), setup.rules?.tuning?.get("labelMax"), "${setup.rules}")
            assertSame(harness.assembly.policy, setup.policy)
            assertSame(harness.screen, setup.screen)
            assertNull(setup.surfaces)
            assertEquals(ReplaySamples.scenario.device.apps, setup.apps?.apps())
        }

    @Test
    fun `the product config is the scenario's names and tuning, every other config at its default`() {
        val tuning = PolicyPins.tuning(tuned.assembly.tuning)
        assertEquals(30, tuning.labelMax)
        assertEquals(
            ProductConfig(setOf("dev.phoneagent", "dev.phoneagent.debug"), setOf("PhoneAgent", "手机助理"), tuning),
            ReplayHarness.config(tuned.assembly, tuning),
        )
    }

    @Test
    fun `every interception point runs the product's default chain`() =
        runTest {
            val defaults = chains(Interceptors())
            assertTrue(
                defaults.keys.containsAll(listOf("preStep", "requestError", "toolPreExecute", "turnStopping")),
                "${defaults.keys}",
            )
            assertTrue(defaults.getValue("requestError").isNotEmpty(), "$defaults")
            assertEquals(defaults, chains(harness(ReplaySamples.scenario).setup.interceptors))
        }

    @Test
    fun `the scenario's output cap and reasoning level go over the product's plan, nothing else`() =
        runTest {
            val capped =
                tuned.copy(assembly = tuned.assembly.copy(maxOutputTokens = 4096, reasoning = ReasoningLevel.HIGH))
            val harness = harness(capped)
            val plan = harness.setup.plan
            assertEquals(4096, plan.maxOutputTokens)
            assertEquals(ReasoningLevel.HIGH, plan.reasoning)
            val product = product(capped, harness.tape)
            assertNull(product.plan.maxOutputTokens)
            assertNull(product.plan.reasoning)
            assertProductParts(product, harness.setup)
        }

    @Test
    fun `the screen shows images when the model sees them, and not when it does not`() =
        runTest {
            val sees = ReplaySamples.info
            val blind = sees.copy(capabilities = sees.capabilities.copy(vision = false))
            assertTrue(harness(tuned, sees).assembly.presenter.vision)
            assertFalse(harness(tuned.copy(model = blind), blind).assembly.presenter.vision)
        }
}
