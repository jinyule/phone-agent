@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.assembly

import dev.phoneagent.core.agent.Interceptors
import dev.phoneagent.core.agent.LoopConfig
import dev.phoneagent.core.agent.LoopHarness
import dev.phoneagent.core.agent.ReasoningDropRecovery
import dev.phoneagent.core.agent.RetryInterceptor
import dev.phoneagent.core.agent.awaitStopped
import dev.phoneagent.core.agent.text
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.policy.rules.PolicyTuning
import dev.phoneagent.core.policy.rules.Refusal
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.StoragePath
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.screen.ScreenFormatConfig
import dev.phoneagent.core.session.PolicyRulesRecorded
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionFiles
import dev.phoneagent.core.session.StopCause
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.InMemoryStoragePort
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.snapshot
import dev.phoneagent.core.testing.Screens.under
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tools.PhaseOneTools
import dev.phoneagent.core.tools.SystemPrompt
import dev.phoneagent.core.tools.ToolsConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The product assembly (phase1 §2.1 `assembly`): one way to build the agent, shared by the app and
 * session replay. Its screen parts come from one format and the main model's vision, and every run
 * records the rules it judges with.
 */
class ProductAssemblyTest {
    private fun model(vision: Boolean): FakeChatModel =
        FakeChatModel(
            info =
                FakeChatModel.INFO.copy(
                    capabilities = ModelCapabilities(tools = true, vision = vision, contextTokens = 200_000),
                ),
        )

    @Test
    fun `the session's parts are the product's tools, prompt, policy and loop configuration`() {
        val loop = LoopConfig(maxStepsPerTurn = 12)
        val tools = ToolsConfig(observeTimeoutMs = 9_000)
        val model = model(vision = false)
        val assembly = assemble(model, PRODUCT.copy(tools = tools, loop = loop))

        val expected = PhaseOneTools.create(assembly.presenter, tools)
        assertEquals(expected.map { it.def }, assembly.registry.tools.map { it.def })
        assertEquals(listOf(ToolGroup.PHONE, ToolGroup.GENERAL), assembly.registry.expandedGroups)
        assertEquals(14, assembly.registry.manifest.size, "every phase-1 tool is visible")
        assertEquals(9_000, assembly.registry["phone_observe"]?.timeoutMs, "the tools' configuration")
        assertSame(model, assembly.plan.model)
        assertEquals(SystemPrompt.BLOCKS, assembly.plan.system)
        assertEquals(assembly.registry.manifest, assembly.plan.tools)
        assertEquals(
            Interceptors().copy(requestError = emptyList()),
            assembly.interceptors.copy(requestError = emptyList()),
        )
        val recoveries = assembly.interceptors.requestError.map { it::class }
        assertTrue(
            recoveries == listOf(RetryInterceptor::class, ReasoningDropRecovery::class),
            "failed requests are retried, then a stale thinking signature is recovered from: $recoveries",
        )

        val setup = assembly.setup(LoopHarness.TASK, files(), screen = null, surfaces = null, apps = null)
        assertSame(assembly.policy, setup.policy)
        assertSame(assembly.intents, setup.intents)
        assertSame(assembly.registry, setup.registry)
        assertSame(assembly.plan, setup.plan)
        assertSame(assembly.interceptors, setup.interceptors)
        assertEquals(loop, setup.config)
        assertEquals(assembly.policyRules, setup.rules)
    }

    @Test
    fun `vision comes from the main model alone and reaches the screen text and the tools`() {
        val sparse = Screens.CHAT.copy(quality = TreeQuality.SPARSE)
        for (vision in listOf(true, false)) {
            val assembly = assemble(model(vision), PRODUCT)
            assertEquals(vision, assembly.presenter.vision, "vision=$vision")
            assertEquals(vision, "截图" in assembly.presenter.page(sparse, SnapshotDetail.BRIEF), "vision=$vision")
        }
    }

    @Test
    fun `the screen port's analyzer and the model's screen text follow the one format`() {
        // With one absorbed piece at most, the row absorbs neither text: the change is e32's own line.
        val click = setOf(ElementState.CLICKABLE)
        val notes = (1..3).map { el("e$it", ElementRole.TEXT, "说明 $it", box(40, it * 100, 960, it * 100 + 40)) }
        val row =
            listOf(
                el("e30", ElementRole.BUTTON, null, box(0, 600, 999, 660), click),
                el("e31", ElementRole.TEXT, "订单 A", box(40, 610, 400, 650)).under("e30"),
            )
        val before = snapshot("s1", notes + row + badge("待付款"))
        val after = snapshot("s2", notes + row + badge("已发货"))

        fun changeShown(format: ScreenFormatConfig): String {
            val assembly = assemble(model(vision = true), PRODUCT.copy(format = format))
            val diff = assembly.analyzer.diff(before, after)
            return assembly.presenter.afterAction(ActionOutcome(Status.Ok, Effect.CHANGED, after, diff), before.id)
        }

        assertEquals(
            "screen s2 · 与 s1 相比的变化（其余不变）\n~ @e32 \"已发货\"",
            changeShown(ScreenFormatConfig(absorbMaxPieces = 1)),
        )
        assertEquals(
            "screen s2 · 与 s1 相比的变化（其余不变）\n~ @e30 按钮 \"订单 A · 已发货\" (499,630)",
            changeShown(ScreenFormatConfig()),
        )
    }

    private fun badge(text: String) = el("e32", ElementRole.TEXT, text, box(600, 610, 960, 650)).under("e30")

    @Test
    fun `the rules every run records are the version and every threshold the policy judges with`() {
        val tuning = PolicyTuning(labelMax = 14, maxDepth = 60)
        val rules = assemble(model(vision = false), PRODUCT.copy(tuning = tuning)).policyRules

        assertEquals(PolicyTuning.RULES_VERSION, rules.rulesVersion)
        assertEquals(TUNING_FIELDS, rules.tuning.keys.toList(), "every field, defaults included, in order")
        assertEquals(
            14,
            rules.tuning
                .getValue("labelMax")
                .jsonPrimitive.int,
        )
        assertEquals(
            60,
            rules.tuning
                .getValue("maxDepth")
                .jsonPrimitive.int,
        )
        assertEquals(tuning.byName(), rules.tuning.mapValues { (_, value) -> value.jsonPrimitive.int })
        val defaults = assemble(model(vision = false), PRODUCT).policyRules.tuning
        assertEquals(PolicyTuning().byName(), defaults.mapValues { (_, value) -> value.jsonPrimitive.int })
    }

    @Test
    fun `the policy judges with the tuning the run records`() =
        runTest {
            suspend fun observed(tuning: PolicyTuning): AssembledLoop {
                val model = model(vision = false).calls(call("c0", "phone_observe")).answers("好")
                return AssembledLoop(this, model, ScriptedScreen(Screens.CHAT), PRODUCT.copy(tuning = tuning)).ran()
            }
            val unreadable = Refusal.UNREADABLE_SCREEN.hint
            val read = observed(PolicyTuning()).resultOf("c0").text()
            assertFalse(unreadable in read, read)
            assertTrue("张三" in read, read)

            val few = observed(PolicyTuning(maxElements = 1))
            val withheld = few.resultOf("c0").text()
            assertTrue(unreadable in withheld, withheld)
            assertFalse("张三" in withheld, withheld)
            val recorded =
                few
                    .events()
                    .filterIsInstance<PolicyRulesRecorded>()
                    .single()
                    .tuning
            assertEquals(1, recorded.getValue("maxElements").jsonPrimitive.int)
        }

    @Test
    fun `an assembled task records its rules first, and again when it is resumed`() =
        runTest {
            val harness = AssembledLoop(this, FakeChatModel().answers("好了"), ScriptedScreen(Screens.CHAT))
            val rules = harness.assembly.policyRules
            val loop = harness.start()
            assertEquals(listOf<SessionEvent>(LoopHarness.ATTENDED, rules), harness.events())
            loop.stop(StopCause.USER)
            loop.awaitStopped()
            harness.resume()
            assertEquals(rules, harness.events().last())
            assertEquals(2, harness.events().count { it is PolicyRulesRecorded })
        }

    @Test
    fun `invalid own names fail the assembly loudly`() {
        assertThrows<IllegalArgumentException> {
            assemble(
                model(vision = false),
                PRODUCT.copy(ownPackages = emptySet()),
            )
        }
        assertThrows<IllegalArgumentException> { assemble(model(vision = false), PRODUCT.copy(ownLabels = setOf(" "))) }
    }

    private fun files() = SessionFiles(InMemoryStoragePort(), StoragePath("sessions/s1"))

    private companion object {
        /** `PolicyTuning`'s fields in declaration order (policy-rules §8). */
        val TUNING_FIELDS =
            listOf(
                "labelMax",
                "contextMax",
                "shortText",
                "totalTextMax",
                "labelRead",
                "titleBand",
                "clearNear",
                "rowSwipeMin",
                "rowMinWidth",
                "carouselMax",
                "maxElements",
                "maxScreenText",
                "maxDepth",
                "totalsRead",
            )
    }
}
