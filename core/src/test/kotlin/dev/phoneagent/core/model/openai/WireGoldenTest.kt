package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.ToolDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Neutral request → wire JSON goldens (`wire/golden/`). One conversation exercises every mapping
 * rule: system blocks, text and image user turns, the model's own and foreign reasoning, tool
 * calls, text / image / error results, reasoning level and output cap.
 */
class WireGoldenTest {
    private val screen = Kit.blob('a')
    private val shot = Kit.blob('b')
    private val images = ImageData(mapOf(screen to "U0NSRUVO", shot to "U0hPVA=="))
    private val other = ProviderId("other")
    private val tap = ToolDef("tap", "Taps a point.", Kit.WEATHER_SCHEMA)

    private fun reasoning(
        kind: ReasoningKind,
        opaque: JsonObject,
        provider: ProviderId = Kit.PROVIDER,
        model: ModelId = Kit.MODEL,
    ) = Part.Reasoning(provider, model, kind, opaque, summary = "summary text is never sent")

    private val encrypted =
        buildJsonObject {
            put("id", "rs_1")
            put("type", "reasoning")
            putJsonArray("summary") {}
            put("encrypted_content", "gAAAA-test-encrypted")
        }

    private val conversation =
        ChatRequest(
            system = listOf(PromptBlock("role", "You operate a phone."), PromptBlock("rules", "Be careful.")),
            wire = Kit.UNPREPARED,
            tools = listOf(Kit.WEATHER, tap),
            messages =
                listOf(
                    Message(
                        Role.USER,
                        listOf(Part.Text("Open settings."), Part.Image(screen, "image/png", 1080, 2400)),
                    ),
                    Message(
                        Role.ASSISTANT,
                        listOf(
                            reasoning(
                                ReasoningKind.CHAT_REASONING_FIELD,
                                buildJsonObject { put("reasoning_content", "Tap the gear.") },
                            ),
                            reasoning(ReasoningKind.RESPONSES_ENCRYPTED, encrypted),
                            reasoning(
                                ReasoningKind.CHAT_REASONING_FIELD,
                                buildJsonObject { put("reasoning_content", "foreign") },
                                other,
                            ),
                            reasoning(ReasoningKind.RESPONSES_ENCRYPTED, encrypted, model = ModelId("older-model")),
                            reasoning(ReasoningKind.RESPONSES_ENCRYPTED, buildJsonObject { put("type", "reasoning") }),
                            Part.Text("Tapping the gear."),
                            Part.ToolCall(ToolCallId("call_1"), "tap", "{\"x\":1,\"y\":2}"),
                            Part.ToolCall(ToolCallId("call_2"), "get_weather", "not json, kept verbatim"),
                        ),
                    ),
                    Message(
                        Role.TOOL,
                        listOf(
                            Part.ToolResult(
                                ToolCallId("call_1"),
                                listOf(Part.Text("Tapped."), Part.Image(shot, "image/jpeg", 540, 1200)),
                                isError = false,
                            ),
                            Part.ToolResult(
                                ToolCallId("call_2"),
                                listOf(Part.Text("error: unknown city")),
                                isError = true,
                            ),
                        ),
                    ),
                    Message(
                        Role.ASSISTANT,
                        listOf(Part.ToolCall(ToolCallId("call_3"), "tap", "{}")),
                    ),
                    Message(
                        Role.TOOL,
                        listOf(
                            Part.ToolResult(
                                ToolCallId("call_3"),
                                listOf(Part.Image(shot, "image/jpeg", 540, 1200)),
                                isError = false,
                            ),
                        ),
                    ),
                    Message(Role.ASSISTANT, listOf(Part.Text("Settings are open."))),
                    Message(Role.USER, listOf(Part.Text("Thanks."), Part.Text("Now Wi-Fi."))),
                ),
            maxOutputTokens = 2048,
            reasoning = ReasoningLevel.HIGH,
        )

    /** [request] mapped by a Chat model of these settings: its snapshot as `prepare` gives it, then the body. */
    @Suppress("LongParameterList") // Named test defaults; every caller sets a few.
    private fun chat(
        request: ChatRequest,
        vision: Boolean,
        echo: Boolean,
        images: ImageData = this.images,
        field: String? = "reasoning_content",
        levels: List<ReasoningLevel>? = null,
        quirks: ChatQuirks = ChatQuirks.DEFAULT,
    ): JsonObject {
        val profile = Kit.profile(BASE, reasoningField = field, echoReasoning = echo)
        val prepared = prepared(request, profile, Kit.capabilities(vision, levels), WireOptions(chat = quirks))
        return ChatCompletionsWire(Kit.PROVIDER, Kit.MODEL, prepared.wire).body(prepared, images)
    }

    /** [request] mapped by a Responses model of these settings, like [chat]. */
    private fun responses(
        request: ChatRequest,
        vision: Boolean,
        includeReasoning: Boolean,
        images: ImageData = this.images,
        plan: Boolean = false,
    ): JsonObject {
        val profile =
            Kit.profile(BASE, ModelProtocol.OPENAI_RESPONSES, credential = if (plan) Kit.PLAN else Kit.KEY)
        val options = WireOptions(responses = ResponsesQuirks(includeReasoning))
        val prepared = prepared(request, profile, Kit.capabilities(vision), options)
        return ResponsesWire(Kit.PROVIDER, Kit.MODEL, prepared.wire).body(prepared, images)
    }

    private fun prepared(
        request: ChatRequest,
        profile: ProviderProfile,
        capabilities: ModelCapabilities,
        options: WireOptions,
    ): ChatRequest {
        val settings = WireRules.of(profile, capabilities, options).prepare(request.reasoning)
        return request.copy(reasoning = settings.reasoning, wire = settings.wire)
    }

    @Test
    fun `chat completions with vision and echoed reasoning`() {
        val body = chat(conversation, vision = true, echo = true)
        Kit.assertGolden("golden/chat-vision-echo.json", body)
    }

    /** Calls answered over several tool messages, one result with an image. */
    private val splitResults =
        ChatRequest(
            system = emptyList(),
            wire = Kit.UNPREPARED,
            tools = listOf(Kit.WEATHER, tap),
            messages =
                listOf(
                    Kit.user("Check the screen."),
                    Message(
                        Role.ASSISTANT,
                        listOf(
                            Part.ToolCall(ToolCallId("call_a"), "tap", "{}"),
                            Part.ToolCall(ToolCallId("call_b"), "get_weather", "{}"),
                            Part.ToolCall(ToolCallId("call_c"), "tap", "{}"),
                        ),
                    ),
                    Message(
                        Role.TOOL,
                        listOf(
                            Part.ToolResult(
                                ToolCallId("call_a"),
                                listOf(Part.Text("Tapped."), Part.Image(shot, "image/jpeg", 540, 1200)),
                                isError = false,
                            ),
                        ),
                    ),
                    Message(
                        Role.TOOL,
                        listOf(Part.ToolResult(ToolCallId("call_b"), listOf(Part.Text("Sunny.")), isError = false)),
                    ),
                    Message(
                        Role.TOOL,
                        listOf(
                            Part.ToolResult(
                                ToolCallId("call_c"),
                                listOf(Part.Image(shot, "image/jpeg", 540, 1200)),
                                isError = false,
                            ),
                        ),
                    ),
                    Message(Role.ASSISTANT, listOf(Part.Text("Done."))),
                ),
        )

    @Test
    fun `chat completions put tool images after the whole run of tool messages`() {
        val body = chat(splitResults, vision = true, echo = true)
        val messages = (body["messages"] as JsonArray).map { it as JsonObject }
        assertEquals(
            listOf("user", "assistant", "tool", "tool", "tool", "user", "assistant"),
            messages.map { (it["role"] as JsonPrimitive).content },
            "no user message between the results of one assistant turn",
        )
        val follow = messages[5]["content"] as JsonArray
        val captions = follow.mapNotNull { ((it as JsonObject)["text"] as? JsonPrimitive)?.content }
        assertEquals(listOf("Images from tool call call_a:", "Images from tool call call_c:"), captions)
        Kit.assertGolden("golden/chat-tool-results-split.json", body)

        val endingInResults = splitResults.copy(messages = splitResults.messages.dropLast(1))
        val next = chat(endingInResults, vision = true, echo = true)
        assertEquals(
            messages.dropLast(1),
            (next["messages"] as JsonArray).map { it as JsonObject },
            "a request that ends in results still gets their images",
        )
    }

    @Test
    fun `chat completions without vision, echo or optional fields`() {
        val options =
            ChatQuirks(
                parallelToolCalls = false,
                streamUsage = false,
                maxTokensField = MaxTokensField.MAX_COMPLETION_TOKENS,
                reasoningEffort = ReasoningEffortStyle.OPENROUTER,
            )
        val body =
            chat(
                conversation,
                vision = false,
                echo = false,
                images = ImageData.NONE,
                levels = listOf(ReasoningLevel.LOW),
                quirks = options,
            )
        Kit.assertGolden("golden/chat-text-only.json", body)
    }

    @Test
    fun `chat completions minimal request`() {
        val minimal =
            ChatRequest(
                emptyList(),
                emptyList(),
                listOf(Kit.user("Hi")),
                Kit.UNPREPARED,
                reasoning = ReasoningLevel.LOW,
            )
        val noEffort = ChatQuirks(reasoningEffort = ReasoningEffortStyle.NONE)
        val body = chat(minimal, vision = true, echo = true, images = ImageData.NONE, quirks = noEffort)
        Kit.assertGolden("golden/chat-minimal.json", body)
    }

    @Test
    fun `responses with vision and encrypted reasoning`() {
        val body = responses(conversation, vision = true, includeReasoning = true)
        Kit.assertGolden("golden/responses-vision.json", body)
    }

    @Test
    fun `responses without vision or include`() {
        val minimal =
            conversation.copy(
                system = emptyList(),
                tools = emptyList(),
                maxOutputTokens = null,
                reasoning = null,
            )
        val body = responses(minimal, vision = false, includeReasoning = false, images = ImageData.NONE)
        Kit.assertGolden("golden/responses-text-only.json", body)
    }

    @Test
    fun `plan mode cuts the request to the allowed fields`() {
        val body = PlanMode.enforce(responses(conversation, vision = true, includeReasoning = true, plan = true))
        Kit.assertGolden("golden/responses-plan.json", body)
        assertFalse("max_output_tokens" in body)
    }

    @Test
    fun `wire bodies are pure functions of the request`() {
        assertEquals(chat(conversation, vision = true, echo = true), chat(conversation, vision = true, echo = true))
        val withoutEcho = chat(conversation, vision = true, echo = false)
        assertNotEquals(chat(conversation, vision = true, echo = true), withoutEcho)
        assertFalse("reasoning_content" in withoutEcho.toString())
    }

    @Test
    fun `plan mode refuses hosted tools and rewrites system items`() {
        val body =
            buildJsonObject {
                put("model", "m")
                putJsonArray("input") {
                    add(
                        buildJsonObject {
                            put("type", "message")
                            put("role", "system")
                            put("content", "s")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("type", "message")
                            put("role", "user")
                            put("content", "u")
                        },
                    )
                    add(JsonPrimitive("not an item"))
                }
                putJsonArray("tools") {
                    add(
                        buildJsonObject {
                            put("type", "custom")
                            put("name", "c")
                        },
                    )
                }
                put("store", true)
                put("stream", false)
                put("temperature", 0.2)
                put("previous_response_id", "resp_1")
                putJsonObject("metadata") { put("k", "v") }
            }
        val enforced = PlanMode.enforce(body)
        assertEquals(setOf("model", "input", "tools", "store", "stream"), enforced.keys)
        assertEquals(JsonPrimitive(false), enforced["store"])
        assertEquals(JsonPrimitive(true), enforced["stream"])
        val roles = (enforced["input"] as JsonArray).mapNotNull { (it as? JsonObject)?.get("role") }
        assertEquals(listOf(JsonPrimitive("developer"), JsonPrimitive("user")), roles)

        val untyped = JsonObject(body + ("tools" to JsonArray(listOf(JsonPrimitive("x")))))
        assertThrows<ModelError.InvalidRequest> { PlanMode.enforce(untyped) }
        val noInput = PlanMode.enforce(buildJsonObject { put("model", "m") })
        assertEquals(setOf("model", "store", "stream"), noInput.keys)
    }

    private fun withTool(type: String) =
        buildJsonObject {
            put("model", "m")
            putJsonArray("tools") {
                add(
                    buildJsonObject {
                        put("type", type)
                        put("name", "t")
                    },
                )
            }
        }

    /** Only listed tool types pass: anything the plan channel does not run is refused, known or new. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "web_search", "web_search_preview", "file_search", "computer_use_preview", "code_interpreter",
            "image_generation", "mcp", "tool_search", "local_shell", "shell", "apply_patch", "a_future_hosted_tool",
        ],
    )
    fun `plan mode refuses every tool type but function and custom`(type: String) {
        val error = assertThrows<ModelError.InvalidRequest> { PlanMode.enforce(withTool(type)) }
        assertEquals("tools", error.param)
    }

    @ParameterizedTest
    @ValueSource(strings = ["function", "custom"])
    fun `plan mode keeps function and custom tools`(type: String) {
        assertEquals(withTool(type)["tools"], PlanMode.enforce(withTool(type))["tools"])
    }
}

private const val BASE = "http://127.0.0.1:1/v1"
