@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.phoneagent.core.assembly

import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.rules.Step
import dev.phoneagent.core.policy.rules.Steps
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeTool
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import dev.phoneagent.core.testing.Screens.under
import dev.phoneagent.core.testing.ScriptedScreen
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolCall
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * What the policy is told a call does must be what the tool does (policy-rules §7): every tool of
 * the assembled registry is known to the resolver or only reads (contracts addendum #1 §7), and
 * every spelling of an argument the tool accepts is read by the resolver as what the action the
 * tool then performs means, written out here without the resolver.
 */
class ResolverCoverageTest {
    private val assembly = assemble(FakeChatModel(), PRODUCT)

    /** The registered tools the resolver does not know and that do more than read. */
    private fun unjudged(tools: List<Tool>): List<String> =
        tools
            .filter { it.risk != RiskClass.READ && Steps.parse(call(it.def.name, "{}")) == Step.Unknown }
            .map { it.def.name }

    @Test
    fun `every registered tool is known to the resolver or only reads`() {
        assertEquals(emptyList<String>(), unjudged(assembly.registry.tools))
        assertEquals(14, assembly.registry.tools.size)
        // The check catches a tool the resolver was not taught; a reading one may stay unknown.
        val extra = listOf(FakeTool("phone_collect", RiskClass.WRITE), FakeTool("phone_peek", RiskClass.READ))
        assertEquals(listOf("phone_collect"), unjudged(assembly.registry.tools + extra))
    }

    /**
     * A probe page: at (100,100) a short press reaches only 返回 (e1), a long press also 删除 (e2);
     * an input field (e3) in a list (e4).
     */
    private val probe: ScreenSnapshot =
        ScriptedScreen.snapshotOf(
            "s30",
            pkg = "com.example.notes",
            elements =
                listOf(
                    el("e1", ElementRole.BUTTON, "返回", box(0, 0, 200, 200), setOf(ElementState.CLICKABLE)),
                    el("e2", ElementRole.TEXT, "删除", box(0, 0, 200, 200), setOf(ElementState.LONG_CLICKABLE)),
                    el("e4", ElementRole.LIST, "笔记", box(0, 300, 999, 900), setOf(ElementState.SCROLLABLE)),
                    el(
                        "e3",
                        ElementRole.INPUT,
                        bounds = box(100, 400, 860, 460),
                        states = setOf(ElementState.EDITABLE, ElementState.CLICKABLE),
                    ).under("e4"),
                ),
        )

    /** Each tool with argument spellings, and whether the tool accepts them (else it does nothing). */
    private val spellings: Map<String, List<Pair<String, Boolean>>> =
        mapOf(
            "phone_tap" to
                listOf(
                    """{"screen":"s30","ref":"e1"}""" to true,
                    """{"screen":" s30 ","ref":"@e1"}""" to true,
                    """{"screen":"s30","ref":" e1 "}""" to true,
                    """{"screen":"s30","ref":" @e1\t"}""" to true,
                    """{"screen":"s30","ref":"1"}""" to false,
                    """{"screen":"s30","ref":"E1"}""" to false,
                    """{"screen":"s30","ref":"e1","x":null,"y":null}""" to true,
                    """{"screen":"s30","x":100,"y":100}""" to true,
                    """{"screen":"s30","x":100.0,"y":"100"}""" to true,
                    """{"screen":"s30","x":"100.0","y":1e2}""" to true,
                    """{"screen":"s30","x":" 100","y":100}""" to true,
                    """{"screen":"s30","x":100.5,"y":100}""" to false,
                    """{"screen":"s30","x":1000,"y":100}""" to false,
                    """{"screen":"s30","ref":"e1","long":true}""" to true,
                    """{"screen":"s30","ref":"e1","long":"true"}""" to true,
                    """{"screen":"s30","ref":"e1","long":false}""" to true,
                    """{"screen":"s30","ref":"e1","long":"false"}""" to true,
                    """{"screen":"s30","ref":"e1","long":null}""" to true,
                    """{"screen":"s30","ref":"e1","long":"TRUE"}""" to true,
                    """{"screen":"s30","ref":"e1","long":1}""" to false,
                    """{"screen":"s30","x":100,"y":100,"long":"true"}""" to true,
                    """{"screen":"s30","x":"100","y":100,"long":true}""" to true,
                ),
            "phone_type" to
                listOf(
                    """{"screen":"s30","ref":"e3","text":"买菜"}""" to true,
                    """{"screen":"s30","ref":"@e3","text":5}""" to true,
                    """{"screen":"s30","ref":" e3 ","text":true}""" to true,
                    """{"screen":"s30","text":"买菜"}""" to true,
                    """{"screen":"s30","ref":null,"text":"买菜"}""" to true,
                    """{"screen":"s30","ref":"e3","text":"买菜","submit":true}""" to true,
                    """{"screen":"s30","ref":"e3","text":"买菜","submit":"true"}""" to true,
                    """{"screen":"s30","ref":"e3","text":"买菜","submit":"false","append":"true"}""" to true,
                    """{"screen":"s30","ref":"e3","text":"买菜","append":true,"submit":null}""" to true,
                    """{"screen":"s30","ref":"e3","text":"买菜","submit":"yes"}""" to false,
                ),
            "phone_scroll" to
                listOf(
                    """{"screen":"s30","direction":"down"}""" to true,
                    """{"screen":"s30","direction":"down","ref":"@e4"}""" to true,
                    """{"screen":"s30","direction":"up","ref":" e4"}""" to true,
                    """{"screen":"s30","direction":"down","ref":null}""" to true,
                    """{"screen":"s30","direction":"DOWN"}""" to false,
                    """{"screen":"s30","direction":"down","ref":"4"}""" to false,
                ),
            "phone_swipe" to
                listOf(
                    """{"x1":100,"y1":100,"x2":900,"y2":100,"durationMs":300}""" to true,
                    """{"x1":"100","y1":100.0,"x2":900,"y2":"100","durationMs":"300"}""" to true,
                    """{"x1":100,"y1":100,"x2":999,"y2":100,"durationMs":300.0}""" to true,
                    """{"x1":900,"y1":100,"x2":100,"y2":100,"durationMs":300}""" to true,
                    """{"x1":-1,"y1":100,"x2":900,"y2":100,"durationMs":300}""" to false,
                ),
            "phone_key" to
                listOf(
                    """{"key":"back"}""" to true,
                    """{"key":"enter"}""" to true,
                    """{"key":"home"}""" to true,
                    """{"key":"BACK"}""" to false,
                    """{"key":" back"}""" to false,
                ),
        )

    /**
     * What each action the spellings perform means on [probe], written out without the resolver:
     * the kind, the app, and the element acted on. A swipe acts on the element it starts on, here
     * 返回 (e1) at (100,100) and nothing at (900,100). Enter is outward and acts on no element
     * (nothing on the probe page has focus); Back and Home only move.
     */
    private val meanings: Map<UiAction, ActionIntent> =
        mapOf(
            UiAction.Tap(ref("e1")) to meant(IntentKind.NAVIGATE, "e1"),
            UiAction.Tap(ref("e1"), long = true) to meant(IntentKind.DELETE, "e2"),
            UiAction.Tap(Target.Point(100, 100)) to meant(IntentKind.NAVIGATE, "e1"),
            UiAction.Tap(Target.Point(100, 100), long = true) to meant(IntentKind.DELETE, "e2"),
            UiAction.Type(ref("e3"), "买菜") to meant(IntentKind.INPUT, "e3", FieldClass.PLAIN),
            UiAction.Type(ref("e3"), "5") to meant(IntentKind.INPUT, "e3", FieldClass.PLAIN),
            UiAction.Type(ref("e3"), "true") to meant(IntentKind.INPUT, "e3", FieldClass.PLAIN),
            UiAction.Type(null, "买菜") to meant(IntentKind.INPUT, "e3", FieldClass.PLAIN),
            UiAction.Type(ref("e3"), "买菜", submit = true) to meant(IntentKind.OUTWARD, "e3"),
            UiAction.Type(ref("e3"), "买菜", append = true) to meant(IntentKind.INPUT, "e3", FieldClass.PLAIN),
            UiAction.Scroll(null, Direction.DOWN) to meant(IntentKind.NAVIGATE, null),
            UiAction.Scroll(ref("e4"), Direction.DOWN) to meant(IntentKind.NAVIGATE, "e4"),
            UiAction.Scroll(ref("e4"), Direction.UP) to meant(IntentKind.NAVIGATE, "e4"),
            swipe(100, 900) to meant(IntentKind.NAVIGATE, "e1"),
            swipe(100, 999) to meant(IntentKind.NAVIGATE, "e1"),
            swipe(900, 100) to meant(IntentKind.NAVIGATE, null),
            UiAction.Key(SystemKey.BACK) to meant(IntentKind.NAVIGATE, null),
            UiAction.Key(SystemKey.HOME) to meant(IntentKind.NAVIGATE, null),
            UiAction.Key(SystemKey.ENTER) to meant(IntentKind.OUTWARD, null),
        )

    private fun ref(ref: String) = Target.Ref(ElementRef(ref))

    /** A 300 ms swipe along y=100. */
    private fun swipe(
        fromX: Int,
        toX: Int,
    ) = UiAction.Swipe(Target.Point(fromX, 100), Target.Point(toX, 100), 300)

    /** An action of [kind] on [probe] acting on the element [ref]. */
    private fun meant(
        kind: IntentKind,
        ref: String?,
        fieldClass: FieldClass? = null,
    ): ActionIntent {
        val target = ref?.let { r -> probe.elements.single { it.ref.value == r } }
        return ActionIntent(kind, probe.pkg, target, fieldClass = fieldClass)
    }

    @Test
    fun `every spelling a screen tool accepts is read by the resolver as what its action means`() =
        runTest {
            val performed = mutableSetOf<UiAction>()
            for ((name, cases) in spellings) {
                val tool = checkNotNull(assembly.registry[name]) { name }
                for ((args, accepts) in cases) {
                    val screen = ScriptedScreen(probe)
                    tool.execute(call(name, args), FakeContext(screen))
                    val action = screen.actions.singleOrNull()?.first
                    assertEquals(accepts, action != null, "$name $args: accepted")
                    if (action == null) continue
                    performed += action
                    val meant = checkNotNull(meanings[action]) { "$name $args performed $action, not written out" }
                    assertEquals(meant, intentOf(name, args, tool.risk), "$name $args performed $action")
                }
            }
            assertEquals(meanings.keys, performed, "every meaning written out is performed")
        }

    @Test
    fun `the probe page tells the readings apart`() {
        fun kind(
            name: String,
            args: String,
        ): IntentKind = intentOf(name, args, checkNotNull(assembly.registry[name]).risk).kind

        fun tap(long: Boolean) = """{"screen":"s30","ref":"e1","long":$long}"""
        assertEquals(IntentKind.DELETE, kind("phone_tap", tap(true)), "a long press also reaches 删除")
        assertEquals(IntentKind.NAVIGATE, kind("phone_tap", tap(false)), "a short press only 返回")

        fun point(long: Boolean) = """{"screen":"s30","x":100,"y":100,"long":$long}"""
        assertEquals(IntentKind.DELETE, kind("phone_tap", point(true)), "long at a point")
        assertEquals(IntentKind.NAVIGATE, kind("phone_tap", point(false)), "short at a point")

        fun type(submit: Boolean) = """{"screen":"s30","ref":"e3","text":"买菜","submit":$submit}"""
        assertEquals(IntentKind.OUTWARD, kind("phone_type", type(true)), "submit")
        assertEquals(IntentKind.INPUT, kind("phone_type", type(false)), "no submit")
        assertNotEquals(
            intentOf("phone_tap", """{"screen":"s30","ref":"e1"}""", RiskClass.WRITE),
            intentOf("phone_tap", """{"screen":"s30","ref":"e3"}""", RiskClass.WRITE),
            "the ref",
        )
        assertEquals(IntentKind.OUTWARD, kind("phone_key", """{"key":"enter"}"""), "Enter submits")
        assertEquals(IntentKind.NAVIGATE, kind("phone_key", """{"key":"back"}"""), "Back only moves")

        fun swipeTarget(
            fromX: Int,
            toX: Int,
        ): String? {
            val args = """{"x1":$fromX,"y1":100,"x2":$toX,"y2":100,"durationMs":300}"""
            return intentOf("phone_swipe", args, checkNotNull(assembly.registry["phone_swipe"]).risk).target?.ref?.value
        }
        assertEquals("e1", swipeTarget(100, 900), "a swipe acts where it starts")
        assertEquals(null, swipeTarget(900, 100), "nothing where the reversed swipe starts")
    }

    private fun intentOf(
        name: String,
        args: String,
        risk: RiskClass,
    ): ActionIntent = assembly.intents.resolve(call(name, args), risk, probe)

    private fun call(
        name: String,
        args: String,
    ): ToolCall =
        ToolCall(
            ToolCallId("c1"),
            TaskId("task-1"),
            SessionId("session-1"),
            name,
            Json.parseToJsonElement(args).jsonObject,
            reason = "看",
        )
}
