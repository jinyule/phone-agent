package dev.phoneagent.core.testing

import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A [Tool] for loop tests: an object schema with the required `reason` plus [properties] (all
 * optional unless listed in [required]); [run] decides the result. Records every call it runs.
 */
@Suppress("LongParameterList") // Every parameter but the name has a default.
class FakeTool(
    name: String,
    override val risk: RiskClass = RiskClass.READ,
    override val timeoutMs: Long = 10_000,
    override val group: ToolGroup = ToolGroup.GENERAL,
    properties: Map<String, JsonObject> = emptyMap(),
    required: List<String> = emptyList(),
    private val run: suspend (ToolCall, ToolContext) -> ToolResult = { _, _ -> ToolResult.ok("done") },
) : Tool {
    override val def: ToolDef = ToolDef(name, "Test tool $name.", schemaOf(properties, required))
    override val concurrencySafe: Boolean get() = risk == RiskClass.READ

    /** Calls that reached [execute], in order. */
    val calls = mutableListOf<ToolCall>()

    override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        calls += call
        return run(call, ctx)
    }

    override fun presentCall(call: ToolCall): StepCard = StepCard(def.name, call.reason)

    override fun presentResult(
        call: ToolCall,
        result: ToolResult,
    ): StepCard =
        StepCard(
            def.name,
            call.reason,
            state =
                if (result.status ==
                    ToolStatus.OK
                ) {
                    StepState.SUCCEEDED
                } else {
                    StepState.FAILED
                },
        )

    companion object {
        val STRING: JsonObject = buildJsonObject { put("type", "string") }
        val INTEGER: JsonObject = buildJsonObject { put("type", "integer") }

        fun schemaOf(
            properties: Map<String, JsonObject>,
            required: List<String> = emptyList(),
        ): JsonObject =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    put(ToolCall.REASON, STRING)
                    properties.forEach { (key, schema) -> put(key, schema) }
                }
                putJsonArray("required") {
                    add(kotlinx.serialization.json.JsonPrimitive(ToolCall.REASON))
                    required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                }
                put("additionalProperties", false)
            }
    }
}
