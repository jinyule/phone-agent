package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.StepCard
import dev.phoneagent.core.tool.StepDetail
import dev.phoneagent.core.tool.StepState
import dev.phoneagent.core.tool.Tool
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared shape of the phase-1 tools (phase1 §5): the definition built from a [ToolSchema], argument
 * reading against the declared names, and trajectory cards.
 *
 * Cards are pure functions of the call and result: the summary is the call's `reason`; details
 * are the given arguments in schema order, then the result's `details`, all keyed by name (the
 * app maps keys to labels); summary and details are passed through [mask]; the image is the
 * result's first image.
 *
 * Masking in the model-visible result text: tools mask what comes from the screen or the device.
 * They do not mask the model's own arguments echoed back (the model wrote them, and an echo that
 * differs from its arguments would only confuse it) nor what the user answered in ask_user (the
 * user gave it to the agent on purpose, e.g. a card number to fill into a form).
 */
internal abstract class PhaseOneTool(
    name: String,
    description: String,
    private val mask: (String) -> String,
    declare: ToolSchema.() -> Unit,
) : Tool {
    private val schema = ToolSchema.of(declare)

    final override val def: ToolDef = ToolDef(name, description, schema.first)

    /** Argument names the schema declares, without `reason`. */
    protected val argNames: Set<String> = schema.second

    /** A reader for [call]'s arguments. */
    protected fun reader(call: ToolCall): ArgReader = ArgReader(call.args, argNames)

    final override fun presentCall(call: ToolCall): StepCard = StepCard(call.name, mask(call.reason), argDetails(call))

    final override fun presentResult(
        call: ToolCall,
        result: ToolResult,
    ): StepCard {
        val resultDetails =
            result.details.orEmpty().mapNotNull { (key, value) -> display(value)?.let { StepDetail(key, mask(it)) } }
        return StepCard(
            tool = call.name,
            summary = mask(call.reason),
            details = argDetails(call) + resultDetails,
            image =
                result.content
                    .filterIsInstance<Part.Image>()
                    .firstOrNull()
                    ?.blob,
            state = stateOf(result),
        )
    }

    private fun argDetails(call: ToolCall): List<StepDetail> =
        argNames.mapNotNull { key -> call.args[key]?.let(::display)?.let { StepDetail(key, mask(it)) } }

    private fun display(value: JsonElement): String? =
        when (value) {
            is JsonNull -> null
            is JsonPrimitive -> value.content
            is JsonArray -> value.mapNotNull(::display).joinToString(", ")
            is JsonObject -> value.toString()
        }

    /**
     * Whether a call may change the phone, its screens or where an app is shown, whatever its
     * arguments: then one that was stopped, timed out or left without a result may have taken
     * effect. By default every tool that is not [RiskClass.READ]; hand_to_user only reads but moves
     * the app between the screens, so it says `true`. A tool that changes nothing but is not READ
     * (request_confirmation) keeps `true` too: the safe side.
     */
    internal open val mayChangePhone: Boolean
        get() = risk != RiskClass.READ

    /**
     * Whether [result], an ERROR, leaves it open whether the call took effect, so that its card
     * shows [StepState.UNKNOWN] rather than [StepState.FAILED]. By default never: an ERROR usually
     * did not take effect (see [StepState.FAILED]). hand_to_user says so for an ERROR it did not
     * report itself.
     */
    internal open fun uncertain(result: ToolResult): Boolean = false

    /**
     * The card state of a result. A call that [mayChangePhone] and was stopped
     * ([ToolStatus.CANCELLED]) or timed out while it may already have acted shows as
     * [StepState.UNKNOWN], like [ToolStatus.OUTCOME_UNKNOWN], so the user is not told it failed and
     * does it again. For a call that cannot have changed the phone these three fail. An ERROR
     * fails unless it is [uncertain]. [ToolStatus.NOT_STARTED] never ran and fails.
     */
    private fun stateOf(result: ToolResult): StepState =
        when (result.status) {
            ToolStatus.OK -> StepState.SUCCEEDED

            ToolStatus.REFUSED -> StepState.REFUSED

            ToolStatus.OUTCOME_UNKNOWN,
            ToolStatus.TIMEOUT,
            ToolStatus.CANCELLED,
            -> if (mayChangePhone) StepState.UNKNOWN else StepState.FAILED

            ToolStatus.ERROR -> if (uncertain(result)) StepState.UNKNOWN else StepState.FAILED

            ToolStatus.INVALID_ARGS,
            ToolStatus.SKIPPED,
            ToolStatus.NOT_STARTED,
            -> StepState.FAILED
        }
}
