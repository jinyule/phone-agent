package dev.phoneagent.core.agent

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.testing.FakeChatModel
import dev.phoneagent.core.testing.FakeChatModel.Companion.call
import dev.phoneagent.core.testing.FakeTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

/** The loop's model-visible feedback, kept as golden files (AGENTS.md §5). */
class LoopFeedbackGoldenTest {
    @Test
    fun `the default loop texts`() {
        val texts = LoopTexts()
        val getters =
            LoopTexts::class.java.methods
                .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == String::class.java }
                .sortedBy { it.name }
        val lines =
            getters.map {
                "${it.name.removePrefix(
                    "get",
                ).replaceFirstChar(Char::lowercase)}: ${it.invoke(texts)}"
            }
        LoopGolden.assertMatches("loop-texts.expected.txt", lines.joinToString("\n"))
    }

    private fun schema(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

    private val form =
        FakeTool(
            "fill_form",
            properties =
                mapOf(
                    "n" to
                        schema {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 10)
                        },
                    "mode" to
                        schema {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("fast"))
                                add(JsonPrimitive("slow"))
                            }
                        },
                    "name" to
                        schema {
                            put("type", "string")
                            put("minLength", 1)
                            put("maxLength", 4)
                        },
                    "tags" to
                        schema {
                            put("type", "array")
                            putJsonObject("items") { put("type", "string") }
                            put("maxItems", 2)
                        },
                ),
            required = listOf("n"),
        )

    private val arguments =
        listOf(
            "missing" to """{"reason":"测试"}""",
            "fill_form" to "{not json",
            "fill_form" to """{"reason":"测试"}""",
            "fill_form" to """{"reason":"测试","n":"3"}""",
            "fill_form" to """{"reason":"测试","n":0,"mode":"medium","name":"","tags":["a","b","c"],"extra":1}""",
            "fill_form" to """{"reason":"测试","n":11,"name":"五个汉字呀","tags":[1]}""",
            "fill_form" to """{"reason":"  ","n":1}""",
        )

    @Test
    fun `feedback on calls that do not validate`() =
        runTest {
            val model = FakeChatModel()
            arguments.forEachIndexed { index, (name, json) -> model.calls(call("c${index + 1}", name, json)) }
            model.answers("好了")
            val harness = LoopHarness(this, model, tools = listOf(form))
            val loop = harness.start()
            loop.followup(text("填表"))
            loop.awaitPhase(Phase.Done)

            val results = harness.events().only<ToolResultRecorded>()
            val blocks =
                arguments.zip(results) { (name, json), recorded ->
                    val text = recorded.result.content.joinToString("") { (it as Part.Text).text }
                    "${recorded.callId.value} $name $json\n${recorded.result.status} $text"
                }
            LoopGolden.assertMatches("argument-feedback.expected.txt", blocks.joinToString("\n\n"))
        }
}
