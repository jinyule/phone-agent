package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.screen.ScreenFormatter
import dev.phoneagent.core.testing.FakeContext
import dev.phoneagent.core.testing.FakeInteraction
import dev.phoneagent.core.testing.call
import dev.phoneagent.core.testing.detail
import dev.phoneagent.core.testing.text
import dev.phoneagent.core.tool.EndReason
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.StepDetail
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.ToolControl
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UserToolsTest {
    private val tools = PhaseOneTools.create(ScreenFormatter()).associateBy { it.def.name }

    private suspend fun run(
        tool: String,
        args: String,
        interaction: FakeInteraction = FakeInteraction(),
        reason: String = "测试这一步",
    ): ToolResult = tools.getValue(tool).execute(call(tool, args, reason), FakeContext(interaction = interaction))

    @Test
    fun `general tools never run concurrently, and only request_confirmation is a write`() {
        for (name in listOf("ask_user", "hand_to_user", "request_confirmation", "end_not_done")) {
            val tool = tools.getValue(name)
            assertEquals(ToolGroup.GENERAL, tool.group, name)
            // request_confirmation is not a READ: a resolver that forgets it must not let it through as a read.
            val risk = if (name == "request_confirmation") RiskClass.WRITE else RiskClass.READ
            assertEquals(risk, tool.risk, name)
            assertFalse(tool.concurrencySafe, name)
        }
        assertEquals(5_000, tools.getValue("end_not_done").timeoutMs)
        assertEquals(30_000, tools.getValue("ask_user").timeoutMs)
    }

    @Test
    fun `ask_user asks once and returns the answer`() =
        runTest {
            val interaction = FakeInteraction(AskResult.Answered("十点半"))
            val result = run("ask_user", """{"question":" 几点到？ ","options":[" 十点 ","十点半"]}""", interaction)
            assertEquals(ToolStatus.OK, result.status)
            assertEquals("用户回答：十点半", result.text())
            assertEquals("十点半", result.detail("answer"))
            assertEquals(listOf(Question("几点到？", listOf("十点", "十点半"))), interaction.asked)
            val plain = FakeInteraction()
            run("ask_user", """{"question":"要继续吗？"}""", plain)
            assertEquals(listOf(Question("要继续吗？")), plain.asked)
        }

    @Test
    fun `ask_user tells the model not to guess when there is no answer`() =
        runTest {
            for (outcome in listOf(AskResult.Declined, AskResult.Expired, AskResult.Unavailable)) {
                val result = run("ask_user", """{"question":"选哪个？"}""", FakeInteraction(outcome))
                assertEquals(ToolStatus.ERROR, result.status, "$outcome")
                assertTrue(result.text().contains("end_not_done"), "$outcome")
            }
            val declined = run("ask_user", """{"question":"q"}""", FakeInteraction(AskResult.Declined))
            assertTrue(declined.text().contains("不要替用户猜答案"))
            val interaction = FakeInteraction()
            assertEquals(ToolStatus.INVALID_ARGS, run("ask_user", """{"options":["a"]}""", interaction).status)
            val repeated = run("ask_user", """{"question":"q","options":["a","a"]}""", interaction)
            assertEquals(ToolStatus.INVALID_ARGS, repeated.status)
            assertTrue(interaction.asked.isEmpty())
        }

    @Test
    fun `hand_to_user passes the kind and the call reason, then asks to observe`() =
        runTest {
            val interaction = FakeInteraction()
            val result = run("hand_to_user", """{"kind":"secure_page"}""", interaction, reason = "请输入支付密码")
            assertEquals(ToolStatus.OK, result.status)
            assertTrue(result.text().contains("先 phone_observe 核实结果"))
            assertEquals(listOf(HandoffKind.SECURE_PAGE to "请输入支付密码"), interaction.handoffs)
            val kinds = listOf("login", "captcha", "payment", "choose", "other")
            kinds.forEach { run("hand_to_user", """{"kind":"$it"}""", interaction) }
            assertEquals(
                listOf(
                    HandoffKind.LOGIN,
                    HandoffKind.CAPTCHA,
                    HandoffKind.PAYMENT,
                    HandoffKind.CHOOSE,
                    HandoffKind.OTHER,
                ),
                interaction.handoffs.drop(1).map { it.first },
            )
            assertEquals(ToolStatus.INVALID_ARGS, run("hand_to_user", """{"kind":"password"}""", interaction).status)
        }

    @Test
    fun `hand_to_user explains every way it can fail`() =
        runTest {
            val texts =
                listOf(
                    HandoffResult.DECLINED,
                    HandoffResult.EXPIRED,
                    HandoffResult.UNAVAILABLE,
                    HandoffResult.FAILED,
                    HandoffResult.NOT_TAKEN_BACK,
                ).map { outcome ->
                    val result = run("hand_to_user", """{"kind":"login"}""", FakeInteraction(handoffResult = outcome))
                    assertEquals(ToolStatus.ERROR, result.status, "$outcome")
                    result.text()
                }
            assertEquals(5, texts.toSet().size)
            assertTrue(texts[0].contains("不要绕过这一步"))
            assertTrue(texts[2].contains("end_not_done(cause=blocked)"))
            // FAILED: nothing moved, the user was never asked. NOT_TAKEN_BACK: the app stays on the
            // user's screen, whatever the user answered.
            assertTrue(texts[3].contains("没能把 App 交到用户手上"), texts[3])
            assertTrue(texts[3].contains("用户没有接手"), texts[3])
            assertTrue(texts[4].contains("没能把 App 收回来"), texts[4])
            assertTrue(texts[4].contains("还留在用户的屏幕上"), texts[4])
        }

    @Test
    fun `hand_to_user reports every outcome with its handoff detail, and its card shows it settled`() =
        runTest {
            val handOff = tools.getValue("hand_to_user")
            val call = call("hand_to_user", """{"kind":"login"}""")
            val outcomes =
                HandoffResult.entries.associateWith { outcome ->
                    run("hand_to_user", """{"kind":"login"}""", FakeInteraction(handoffResult = outcome))
                }
            assertEquals(
                HandoffResult.entries.associateWith { it.name.lowercase() },
                outcomes.mapValues { (_, result) -> result.detail("handoff") },
            )
            // The runtime may add a line to the text (the screen note when no screen was read after
            // the handoff): what the tool reported stays settled.
            val noted = outcomes.mapValues { (_, result) -> result.copy(content = result.content + Part.Text("先观察")) }
            for (results in listOf(outcomes, noted)) {
                assertEquals(
                    mapOf(
                        HandoffResult.DONE to StepState.SUCCEEDED,
                        HandoffResult.DECLINED to StepState.FAILED,
                        HandoffResult.EXPIRED to StepState.FAILED,
                        HandoffResult.UNAVAILABLE to StepState.FAILED,
                        HandoffResult.FAILED to StepState.FAILED,
                        HandoffResult.NOT_TAKEN_BACK to StepState.UNKNOWN,
                    ),
                    results.mapValues { (_, result) -> handOff.presentResult(call, result).state },
                )
            }
            assertEquals(
                listOf(StepDetail("kind", "login"), StepDetail("handoff", "declined")),
                handOff.presentResult(call, outcomes.getValue(HandoffResult.DECLINED)).details,
            )
        }

    @Test
    fun `hand_to_user's card shows an error it did not report itself as unknown`() {
        // What the runtime records when handOff threw instead of returning: a failed take-back or
        // a hand-over that did not settle, so the app may be with the user (UserInteraction.handOff).
        val handOff = tools.getValue("hand_to_user")
        val call = call("hand_to_user", """{"kind":"login"}""")
        val failedTakeBack =
            ToolResult.failure(
                ToolStatus.ERROR,
                "工具执行出错，异常类型：dev.phoneagent.core.ports.ScreenUnavailableException\n" +
                    "交接之后还没有读到屏幕：请先调用 phone_observe。",
            )
        assertEquals(StepState.UNKNOWN, handOff.presentResult(call, failedTakeBack).state)
        // Details without the handoff outcome are not the tool's own either.
        val foreign = failedTakeBack.copy(details = buildJsonObject { put("answer", "declined") })
        assertEquals(StepState.UNKNOWN, handOff.presentResult(call, foreign).state)
    }

    @Test
    fun `request_confirmation reports the approval it was run after, without asking again`() =
        runTest {
            val interaction = FakeInteraction()
            val args = """{"action":"发送消息","target":"张三","content":"十点到","amount":"¥0"}"""
            val result = run("request_confirmation", args, interaction)
            assertEquals(ToolStatus.OK, result.status)
            assertTrue(result.text().startsWith("用户已确认：发送消息。"))
            assertTrue(interaction.confirmed.isEmpty())
            assertTrue(interaction.asked.isEmpty())
            val invalid = run("request_confirmation", """{"action":"发送消息","target":"张三"}""")
            assertEquals(ToolStatus.INVALID_ARGS, invalid.status)
            assertEquals(listOf("- 缺少 `content`"), invalid.text().lines().filter { it.startsWith("- ") })
        }

    @Test
    fun `end_not_done ends the turn through the control signal`() =
        runTest {
            val result = run("end_not_done", """{"cause":"blocked","summary":" 微信要求重新登录，需要你先登录。 "}""")
            assertEquals(ToolStatus.OK, result.status)
            assertEquals(ToolControl.EndTurn(EndReason.BLOCKED, "微信要求重新登录，需要你先登录。"), result.control)
            assertEquals("本轮已结束，任务没有完成。用户再发消息时会开始新的一轮。", result.text())
            val causes =
                mapOf(
                    "impossible" to EndReason.IMPOSSIBLE,
                    "user_stopped" to EndReason.USER_STOPPED,
                    "other" to EndReason.OTHER,
                )
            for ((wire, reason) in causes) {
                val ended = run("end_not_done", """{"cause":"$wire","summary":"s"}""")
                assertEquals(ToolControl.EndTurn(reason, "s"), ended.control)
            }
            val invalid = run("end_not_done", """{"why":"blocked","summary":"s"}""")
            assertEquals(ToolStatus.INVALID_ARGS, invalid.status)
            assertNull(invalid.control)
            assertNull(run("end_not_done", """{"cause":"blocked"}""").control)
        }
}
