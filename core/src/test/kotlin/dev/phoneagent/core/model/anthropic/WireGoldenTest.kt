package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.ToolDef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

/**
 * Wire goldens of mapping version 1: neutral request + snapshot → `anthropic-beta` and body.
 * Compare-only ([Kit.assertGolden]); the cases cover the defaults, thinking echo, tool results,
 * images, cache breakpoints and the server-side context edits WP17 will use.
 */
class WireGoldenTest {
    private val wire = MessagesWire(Kit.PROVIDER, Kit.MODEL)
    private val screenshot = Kit.blob('a')
    private val blobs = Kit.blobs(screenshot to "not really a jpeg".toByteArray())

    private fun golden(
        name: String,
        request: ChatRequest,
    ) = runTest {
        val rules = WireRules.of(request, CredentialKind.API_KEY)
        val images = if (rules.inlineImages) ImageData.load(request, blobs) else ImageData.NONE
        val actual =
            buildJsonObject {
                put("anthropic-version", rules.apiVersion)
                put("anthropic-beta", JsonArray(wire.betas(request, rules).map(::JsonPrimitive)))
                put("body", wire.body(request, rules, images))
            }
        Kit.assertGolden("anthropic/golden/$name.json", actual)
    }

    private val thinking =
        Part.Reasoning(
            Kit.PROVIDER,
            Kit.MODEL,
            ReasoningKind.ANTHROPIC_THINKING,
            buildJsonObject {
                put("type", "thinking")
                put("thinking", "先查天气。")
                put("signature", "c2lnbmF0dXJlLW5vdC1yZWFs")
            },
            summary = "先查天气。",
        )

    private val redacted =
        Part.Reasoning(
            Kit.PROVIDER,
            Kit.MODEL,
            ReasoningKind.ANTHROPIC_REDACTED_THINKING,
            buildJsonObject {
                put("type", "redacted_thinking")
                put("data", "cmVkYWN0ZWQtbm90LXJlYWw=")
            },
        )

    /** Reasoning of another provider and of another protocol's kind: never sent here. */
    private val foreign =
        listOf(
            thinking.copy(provider = ProviderId("other")),
            thinking.copy(kind = ReasoningKind.RESPONSES_ENCRYPTED),
        )

    private val compaction =
        Part.Reasoning(
            Kit.PROVIDER,
            Kit.MODEL,
            ReasoningKind.ANTHROPIC_COMPACTION,
            buildJsonObject {
                put("type", "compaction")
                put("content", "早先的对话：用户要查巴黎天气。")
                put("encrypted_content", "Q29tcGFjdGVkLW5vdC1yZWFs")
            },
        )

    @Test
    fun `minimal request on a custom endpoint`() =
        golden(
            "minimal",
            Kit.request(
                tools = emptyList(),
                system = emptyList(),
                reasoning = null,
                wire =
                    Kit.wire(
                        Kit.profile("https://gateway.example.com"),
                        reasoning = null,
                        settings = AnthropicSettings(),
                    ),
                cache = CacheLayout.NONE,
            ),
        )

    @Test
    fun `official defaults with tools, effort and every breakpoint`() =
        golden("official-defaults", Kit.request(wire = Kit.wire()))

    @Test
    fun `thinking is echoed verbatim and only to its own provider`() =
        golden(
            "thinking-echo",
            Kit.request(
                Kit.user("巴黎天气怎么样？"),
                Kit.assistant(*foreign.toTypedArray(), thinking, redacted, Part.Text("我来查一下。"), Kit.call("toolu_01")),
                Kit.tool(Kit.result("toolu_01", Part.Text("晴，22°C"))),
                Kit.assistant(
                    thinking.copy(opaque = buildJsonObject { put("type", "thinking") }),
                    Part.Text("巴黎晴，22°C。"),
                ),
                Kit.user("谢谢"),
                wire = Kit.wire(),
                cache = CacheLayout(tools = true, system = true, recentMessages = 2),
            ),
        )

    @Test
    fun `after a switch from another model its thinking and compaction blocks are still sent`() {
        val sonnet = ModelId("claude-sonnet-5-5")
        golden(
            "model-switch",
            Kit.request(
                Kit.user("巴黎天气怎么样？"),
                Kit.assistant(
                    compaction.copy(model = sonnet),
                    thinking.copy(model = sonnet),
                    Part.Text("我来查一下。"),
                    Kit.call("toolu_01"),
                ),
                Kit.tool(Kit.result("toolu_01", Part.Text("晴，22°C"))),
                Kit.assistant(thinking, Part.Text("巴黎晴，22°C。")),
                Kit.user("谢谢"),
                wire = Kit.wire(),
                cache = CacheLayout(tools = false, system = false, recentMessages = 1),
            ),
        )
    }

    @Test
    fun `tool results open the merged user message, images inline`() =
        golden(
            "tool-results",
            Kit.request(
                Kit.user("打开设置"),
                Kit.assistant(Part.Text(""), Kit.call("toolu_01", """{"reason":"看屏幕"}""")),
                Kit.tool(Kit.result("toolu_01", Part.Text("点击失败"), image(), isError = true)),
                Kit.user("（运行时上下文）当前界面：设置"),
                Message(Role.USER, listOf(image())),
                wire = Kit.wire(),
            ),
        )

    @Test
    fun `images become a placeholder for a model without vision`() =
        golden(
            "placeholder-images",
            Kit.request(
                Message(Role.USER, listOf(Part.Text("看这张图"), image())),
                Kit.assistant(Kit.call("toolu_01")),
                Kit.tool(Kit.result("toolu_01", image())),
                wire = Kit.wire(capabilities = Kit.CAPABILITIES.copy(vision = false)),
            ),
        )

    @Test
    fun `cut-off tool arguments are echoed as an empty object by intent of mapping 1`() =
        golden(
            "cut-arguments",
            Kit.request(
                Kit.user("巴黎天气怎么样？"),
                Kit.assistant(Kit.call("toolu_01", """{"city":"巴"""), Kit.call("toolu_02", "[1]")),
                Kit.tool(Kit.result("toolu_01", Part.Text("参数无效")), Kit.result("toolu_02")),
                reasoning = ReasoningLevel.LOW,
                wire = Kit.wire(reasoning = ReasoningLevel.LOW),
                maxOutputTokens = 4096,
            ),
        )

    @Test
    fun `server-side clearing and compaction`() =
        golden(
            "server-context-edits",
            Kit.request(
                contextEdits =
                    ContextEditPolicy.ServerSide(
                        clearToolUsesAboveTokens = 100_000,
                        keepToolUses = 3,
                        compactAboveTokens = 150_000,
                    ),
                reasoning = ReasoningLevel.MINIMAL,
                wire = Kit.wire(reasoning = ReasoningLevel.MINIMAL),
            ),
        )

    @Test
    fun `strict tools, display omitted, prefix mismatch as an error, bearer and no fallback`() =
        golden(
            "strict-tools",
            Kit.request(
                tools = listOf(strictTool()),
                reasoning = ReasoningLevel.MAX,
                wire =
                    Kit.wire(
                        reasoning = ReasoningLevel.MAX,
                        settings =
                            AnthropicSettings(
                                auth = AnthropicAuth.BEARER,
                                thinkingDisplay = ThinkingDisplay.OMITTED,
                                prefixMismatch = PrefixMismatch.ERROR,
                                eagerInputStreaming = false,
                                strictTools = true,
                            ),
                    ),
            ),
        )

    @Test
    fun `no thinking at all for gateways that refuse it`() =
        golden(
            "no-thinking",
            Kit.request(
                reasoning = ReasoningLevel.XHIGH,
                wire =
                    Kit.wire(
                        reasoning = ReasoningLevel.XHIGH,
                        settings = AnthropicSettings(adaptiveThinking = false, thinkingDisplay = null),
                    ),
                cache = CacheLayout(tools = false, system = false, recentMessages = 1),
            ),
        )

    private fun image() = Part.Image(screenshot, "image/jpeg", width = 720, height = 1280)

    private fun strictTool(): ToolDef =
        ToolDef(
            "tap",
            "点击屏幕上的元素。",
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("target") {
                        put("type", "object")
                        putJsonObject("properties") { putJsonObject("id") { put("type", "string") } }
                        put("additionalProperties", false)
                    }
                    putJsonObject("reason") { put("type", "string") }
                }
                put("additionalProperties", false)
            },
        )
}
