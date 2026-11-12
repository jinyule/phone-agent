package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ActionOutcome.Effect
import dev.phoneagent.core.ports.ActionOutcome.StaleReason
import dev.phoneagent.core.ports.ActionOutcome.Status
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.screen.ScreenDiffer
import dev.phoneagent.core.screen.ScreenFingerprint
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeScreen
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.under
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.detail
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenActionToolsTest {
    private val formatter = ScreenFormatter(lexicon = Screens.LEXICON)
    private val tools = PhaseOneTools.create(formatter).associateBy { it.def.name }
    private val s12 = SnapshotId("s12")
    private val after = Screens.CHAT.copy(id = SnapshotId("s13"))

    private suspend fun run(
        tool: String,
        args: String,
        screen: FakeScreen? = FakeScreen(),
    ): ToolResult = tools.getValue(tool).execute(call(tool, args), FakeContext(screen))

    /** Runs [tool] and returns what it sent to the screen, failing on an error result. */
    private suspend fun sent(
        tool: String,
        args: String,
    ): Pair<UiAction, SnapshotId?> {
        val screen = FakeScreen()
        val result = run(tool, args, screen)
        assertEquals(ToolStatus.OK, result.status, result.text())
        return screen.acts.single()
    }

    @Test
    fun `screen tools write and are never concurrent`() {
        for (name in listOf("phone_tap", "phone_type", "phone_scroll", "phone_swipe", "phone_key")) {
            val tool = tools.getValue(name)
            assertEquals(ToolGroup.PHONE, tool.group, name)
            assertEquals(RiskClass.WRITE, tool.risk, name)
            assertFalse(tool.concurrencySafe, name)
        }
        assertEquals(30_000, tools.getValue("phone_type").timeoutMs)
        assertEquals(20_000, tools.getValue("phone_tap").timeoutMs)
    }

    @Test
    fun `tap by ref or by point, short or long`() =
        runTest {
            assertEquals(
                UiAction.Tap(Target.Ref(ElementRef("e10"))) to s12,
                sent("phone_tap", """{"screen":"s12","ref":"@e10"}"""),
            )
            assertEquals(
                UiAction.Tap(Target.Point(500, 230), long = true) to s12,
                sent("phone_tap", """{"screen":"s12","x":500,"y":230,"long":true}"""),
            )
            val text = run("phone_tap", """{"screen":"s12","ref":"e10","long":true}""").text()
            assertTrue(text.startsWith("已长按 @e10，但不确定有没有效果。"), text)
        }

    @Test
    fun `tap needs exactly one target form`() =
        runTest {
            fun problems(result: ToolResult) = result.text().lines().filter { it.startsWith("- ") }

            assertEquals(
                listOf("- `ref` 和 `x`/`y` 只能给一种"),
                problems(run("phone_tap", """{"screen":"s12","ref":"e1","x":1,"y":2}""")),
            )
            assertEquals(listOf("- 坐标要同时给 `x` 和 `y`"), problems(run("phone_tap", """{"screen":"s12","x":1}""")))
            assertEquals(listOf("- 坐标要同时给 `x` 和 `y`"), problems(run("phone_tap", """{"screen":"s12","y":1}""")))
            assertEquals(
                listOf("- 缺少目标：给 `ref`（最近一屏里的编号），或者同时给 `x` 和 `y`"),
                problems(run("phone_tap", """{"screen":"s12"}""")),
            )
            // An invalid coordinate is reported once, by the range check.
            assertEquals(
                listOf("- `x` 应该是 0–999 的整数（收到 1000）"),
                problems(run("phone_tap", """{"screen":"s12","x":1000,"y":5}""")),
            )
            assertEquals(listOf("- 缺少 `screen`"), problems(run("phone_tap", """{"ref":"e1"}""")))
            val invalid = run("phone_tap", """{"screen":"s12"}""")
            assertEquals(ToolStatus.INVALID_ARGS, invalid.status)
            assertTrue(invalid.text().endsWith("用法：phone_tap(screen, ref 或 x+y, long?)"))
        }

    @Test
    fun `type into a field or the focused one, replacing or appending, optionally submitting`() =
        runTest {
            assertEquals(
                UiAction.Type(Target.Ref(ElementRef("e9")), "十点") to s12,
                sent("phone_type", """{"screen":"s12","ref":"@e9","text":"十点"}"""),
            )
            assertEquals(
                UiAction.Type(null, " ", append = true, submit = true) to s12,
                sent("phone_type", """{"screen":"s12","text":" ","append":true,"submit":true}"""),
            )
            assertTrue(run("phone_type", """{"screen":"s12","text":"好"}""").text().startsWith("已输入到 当前焦点输入框，"))
            assertTrue(
                run("phone_type", """{"screen":"s12","ref":"e9","text":"好","append":true,"submit":true}""")
                    .text()
                    .startsWith("已追加输入到 @e9 并提交，"),
            )
            assertEquals(
                ToolStatus.INVALID_ARGS,
                run("phone_type", """{"screen":"s12","ref":"输入框","text":"好"}""").status,
            )
            assertEquals(ToolStatus.INVALID_ARGS, run("phone_type", """{"screen":"s12"}""").status)
            assertEquals(ToolStatus.INVALID_ARGS, run("phone_type", """{"text":"好"}""").status)
        }

    @Test
    fun `scroll a list or the main one`() =
        runTest {
            assertEquals(
                UiAction.Scroll(null, Direction.DOWN) to s12,
                sent("phone_scroll", """{"screen":"s12","direction":"down"}"""),
            )
            assertEquals(
                UiAction.Scroll(Target.Ref(ElementRef("e5")), Direction.LEFT) to s12,
                sent("phone_scroll", """{"screen":"s12","direction":"left","ref":"e5"}"""),
            )
            val words =
                listOf("up" to "已向上滚动 @e5", "right" to "已向右滚动 @e5", "down" to "已向下滚动 @e5", "left" to "已向左滚动 @e5")
            for ((direction, done) in words) {
                val text = run("phone_scroll", """{"screen":"s12","direction":"$direction","ref":"e5"}""").text()
                assertTrue(text.startsWith(done), text)
            }
            assertEquals(
                ToolStatus.INVALID_ARGS,
                run("phone_scroll", """{"screen":"s12","direction":"forward"}""").status,
            )
            assertEquals(
                ToolStatus.INVALID_ARGS,
                run("phone_scroll", """{"screen":"s12","direction":"up","ref":"list"}""").status,
            )
        }

    @Test
    fun `swipe between two points names no screen`() =
        runTest {
            assertEquals(
                UiAction.Swipe(Target.Point(500, 800), Target.Point(500, 200), 300) to null,
                sent("phone_swipe", """{"x1":500,"y1":800,"x2":500,"y2":200,"durationMs":300}"""),
            )
            assertTrue(
                run(
                    "phone_swipe",
                    """{"x1":1,"y1":2,"x2":3,"y2":4,"durationMs":50}""",
                ).text().startsWith("已从 (1,2) 滑到 (3,4)"),
            )
            val invalid = run("phone_swipe", """{"x1":1,"y1":2,"x2":3,"durationMs":10}""")
            assertEquals(
                listOf("- 缺少 `y2`", "- `durationMs` 应该是 50–5000 的整数（收到 10）"),
                invalid.text().lines().filter {
                    it.startsWith("- ")
                },
            )
        }

    @Test
    fun `system keys`() =
        runTest {
            assertEquals(UiAction.Key(SystemKey.BACK) to null, sent("phone_key", """{"key":"back"}"""))
            assertEquals(UiAction.Key(SystemKey.HOME) to null, sent("phone_key", """{"key":"home"}"""))
            assertEquals(UiAction.Key(SystemKey.ENTER) to null, sent("phone_key", """{"key":"enter"}"""))
            assertTrue(run("phone_key", """{"key":"home"}""").text().startsWith("已按主页键"))
            assertEquals(ToolStatus.INVALID_ARGS, run("phone_key", """{"key":"power"}""").status)
            assertEquals(ToolStatus.INVALID_ARGS, run("phone_key", """{"key":"back","extra":1}""").status)
        }

    @Test
    fun `no screen and an unusable screen are error results, nothing is sent`() =
        runTest {
            assertEquals(ToolResults.NO_SCREEN, run("phone_tap", """{"screen":"s12","ref":"e1"}""", screen = null))
            val gone = FakeScreen().apply { unavailable = ScreenUnavailableException("虚拟屏已释放") }
            val result = run("phone_key", """{"key":"back"}""", gone)
            assertEquals(ToolStatus.ERROR, result.status)
            assertEquals("屏幕暂时用不了（虚拟屏已释放），这一步可能没有执行。过一会儿先 phone_observe，再决定下一步。", result.text())
            assertTrue(gone.acts.isEmpty())
        }

    @Test
    fun `every effect has its own status line`() =
        runTest {
            val lines =
                Effect.entries.associateWith { effect ->
                    val screen =
                        FakeScreen().apply {
                            onAct =
                                {
                                    _,
                                    _,
                                    ->
                                    ActionOutcome(Status.Ok, effect, after, ScreenDiff(base = SnapshotId("s12")))
                                }
                        }
                    run("phone_key", """{"key":"back"}""", screen).text().lines().first()
                }
            assertEquals("已按返回键。", lines[Effect.CHANGED])
            assertTrue(lines.getValue(Effect.NO_CHANGE).contains("画面没有变化"))
            assertTrue(lines.getValue(Effect.NEW_APP).contains("另一个 App"))
            assertTrue(lines.getValue(Effect.SPILLED_BACK).contains("挪回后台屏"))
            assertTrue(lines.getValue(Effect.RATE_LIMITED).contains("太频繁"))
            assertTrue(lines.getValue(Effect.SECURE_PAGE).contains("hand_to_user(kind=secure_page)"))
            assertTrue(lines.getValue(Effect.UNKNOWN).contains("不确定"))
            assertEquals(Effect.entries.size, lines.values.toSet().size)
        }

    /** The tap result when the port saw no change from before to after the action but [diff] is what the model gets. */
    private suspend fun tapWithoutPortChange(
        shown: List<UiElement>,
        diff: ScreenDiff,
    ): String {
        val outcome = ActionOutcome(Status.Ok, Effect.NO_CHANGE, after.copy(elements = shown), diff)
        return run(
            "phone_tap",
            """{"screen":"s12","ref":"e10"}""",
            FakeScreen().apply {
                onAct = {
                    _,
                    _,
                    ->
                    outcome
                }
            },
        ).text()
    }

    private fun assertNoChangeWithDiff(text: String) {
        assertTrue(text.startsWith("已点击 @e10，但动作前后画面没有变化"), text)
        assertTrue(text.contains("和你上次看到的画面相比的变化见下面，不一定是这个动作造成的。"), text)
        assertTrue(text.contains("\nscreen s13 · 与 s12 相比的变化（其余不变）\n"), text)
        assertFalse(text.contains("没有可见变化"), text)
    }

    @Test
    fun `no change from the port with a non-empty diff says both, the action did nothing and what differs`() =
        runTest {
            val print = { e: List<UiElement> -> ScreenFingerprint.of(after.pkg, after.activity, e, null) }
            // Only the messages' parent changed, which the fingerprint ignores.
            val reparented = Screens.CHAT_ELEMENTS.map { if (it.parent?.value == "e5") it.copy(parent = null) else it }
            assertEquals(print(Screens.CHAT_ELEMENTS), print(reparented))
            val parentOnly =
                tapWithoutPortChange(reparented, ScreenDiffer().diff(Screens.CHAT, after.copy(elements = reparented)))
            assertNoChangeWithDiff(parentOnly)
            assertTrue(parentOnly.contains("~ @e6 \"张三：明天几点到？\""), parentOnly)
            // Only a ref changed (the input box e9 is now e12), which the fingerprint ignores too.
            val renamed =
                Screens.CHAT_ELEMENTS.map {
                    if (it.ref.value ==
                        "e9"
                    ) {
                        it.copy(ref = ElementRef("e12"))
                    } else {
                        it
                    }
                }
            assertEquals(print(Screens.CHAT_ELEMENTS), print(renamed))
            val refOnly =
                tapWithoutPortChange(renamed, ScreenDiffer().diff(Screens.CHAT, after.copy(elements = renamed)))
            assertNoChangeWithDiff(refOnly)
            assertTrue(refOnly.contains("+ @e12 输入框") && refOnly.contains("- @e9"), refOnly)
            // A message came in after the model's read and before the action, which then did nothing.
            val message = Screens.el("e11", ElementRole.TEXT, "张三：在吗", Screens.box(40, 400, 700, 460)).under("e5")
            val arrived = Screens.CHAT_ELEMENTS + message
            val background =
                tapWithoutPortChange(arrived, ScreenDiffer().diff(Screens.CHAT, after.copy(elements = arrived)))
            assertNoChangeWithDiff(background)
            assertTrue(background.contains("+ @e11 \"张三：在吗\""), background)
            // With an empty diff the port's "no change" stands alone.
            val unchanged = tapWithoutPortChange(Screens.CHAT_ELEMENTS, ScreenDiff(base = SnapshotId("s12")))
            assertTrue(unchanged.startsWith("已点击 @e10，但画面没有变化：可能还在加载"), unchanged)
            assertFalse(unchanged.contains("不一定是这个动作造成的"), unchanged)
            // A change the port saw is just done.
            val outcome =
                ActionOutcome(
                    Status.Ok,
                    Effect.CHANGED,
                    after.copy(elements = arrived),
                    ScreenDiffer().diff(Screens.CHAT, after.copy(elements = arrived)),
                )
            val changed =
                run(
                    "phone_tap",
                    """{"screen":"s12","ref":"e10"}""",
                    FakeScreen().apply {
                        onAct =
                            { _, _ -> outcome }
                    },
                ).text()
            assertTrue(changed.startsWith("已点击 @e10。\nscreen s13"), changed)
        }

    @Test
    fun `a refusal with a screen after it shows that screen, one without says to observe`() =
        runTest {
            val args = """{"screen":"s12","ref":"e10"}"""
            val withScreen =
                run(
                    "phone_tap",
                    args,
                    FakeScreen().apply {
                        onAct =
                            {
                                _,
                                _,
                                ->
                                ActionOutcome(
                                    Status.Refused("自己的界面"),
                                    Effect.UNKNOWN,
                                    after,
                                    ScreenDiff(base = SnapshotId("s12")),
                                )
                            }
                    },
                )
            assertEquals(ToolStatus.REFUSED, withScreen.status)
            assertTrue(withScreen.text().contains("\nscreen s13 · 与 s12 相比没有可见变化"), withScreen.text())
            assertEquals("s13", withScreen.detail("screen"))
            val blind =
                run(
                    "phone_tap",
                    args,
                    FakeScreen().apply {
                        onAct =
                            { _, _ -> ActionOutcome(Status.Refused("自己的界面"), Effect.UNKNOWN) }
                    },
                )
            assertTrue(blind.text().contains("phone_observe"), blind.text())
            assertNull(blind.detail("screen"))
            // Every outcome with a screen after it shows it, so details.screen always names a screen the model saw.
            for (status in listOf(
                Status.Ok,
                Status.Failed("x"),
                Status.Refused("y"),
                Status.StaleRef(StaleReason.MOVED),
            )) {
                val result =
                    run(
                        "phone_tap",
                        args,
                        FakeScreen().apply {
                            onAct =
                                {
                                    _,
                                    _,
                                    ->
                                    ActionOutcome(status, Effect.CHANGED, after, ScreenDiff(base = SnapshotId("s12")))
                                }
                        },
                    )
                assertTrue(result.text().contains("screen s13"), "$status: ${result.text()}")
                assertEquals("s13", result.detail("screen"), "$status")
            }
        }

    @Test
    fun `failed, refused and stale outcomes are not OK and say what to do`() =
        runTest {
            fun screen(outcome: ActionOutcome) = FakeScreen().apply { onAct = { _, _ -> outcome } }
            val args = """{"screen":"s12","ref":"e10"}"""

            val failed =
                run(
                    "phone_tap",
                    args,
                    screen(
                        ActionOutcome(
                            Status.Failed("找不到输入法"),
                            Effect.UNKNOWN,
                            after,
                            ScreenDiff(base = SnapshotId("s12")),
                        ),
                    ),
                )
            assertEquals(ToolStatus.ERROR, failed.status)
            assertTrue(
                failed.text().startsWith("没有做成：找不到输入法。看一下现在的画面再决定下一步。\nscreen s13 · 与 s12 相比没有可见变化"),
                failed.text(),
            )

            val refused = run("phone_tap", args, screen(ActionOutcome(Status.Refused("系统设置页不允许点击"), Effect.UNKNOWN)))
            assertEquals(ToolStatus.REFUSED, refused.status)
            assertEquals(
                "手机拒绝了这个动作：系统设置页不允许点击。不要重试同一个动作，换个办法，或者 end_not_done。\n" +
                    "（动作后没能读到屏幕，用 phone_observe 查看当前画面）",
                refused.text(),
            )

            val staleBlind =
                run("phone_tap", args, screen(ActionOutcome(Status.StaleRef(StaleReason.NOT_FOUND), Effect.UNKNOWN)))
            assertEquals(ToolStatus.ERROR, staleBlind.status)
            assertEquals(
                "编号过期了（it is no longer on the screen），这一步没有执行。先 phone_observe 读最新一屏，再用新的 screen 和编号。",
                staleBlind.text(),
            )
            assertEquals("NOT_FOUND", staleBlind.detail("staleReason"))

            val stale =
                run(
                    "phone_tap",
                    args,
                    screen(ActionOutcome(Status.StaleRef(StaleReason.UNRELIABLE), Effect.UNKNOWN, after)),
                )
            assertTrue(stale.text().contains("下面是最新一屏，请用它的 screen 和编号：\nscreen s13 · com.tencent.mm"), stale.text())
            assertTrue(stale.text().startsWith("编号过期了（${StaleReason.UNRELIABLE.hint}），这一步没有执行。"), stale.text())
            assertEquals("stale_ref", stale.detail("outcome"))
            assertEquals("UNRELIABLE", stale.detail("staleReason"))
            assertNull(failed.detail("staleReason"))
            assertEquals("s13", stale.detail("screen"))
            assertNull(staleBlind.detail("screen"))
            assertEquals("failed", failed.detail("outcome"))
            assertEquals("refused", refused.detail("outcome"))
            assertEquals("UNKNOWN", refused.detail("effect"))
        }
}
