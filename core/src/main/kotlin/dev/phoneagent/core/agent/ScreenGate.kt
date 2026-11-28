package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.IntentResolver
import dev.phoneagent.core.policy.PolicyEngine
import dev.phoneagent.core.policy.SCREEN_JUDGMENT_TOOL
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Judges a new screen a tool call read before the call's tool gets it (policy-rules §6.4): as a
 * [SCREEN_JUDGMENT_TOOL] call with no arguments and the reading call's id, task, session and
 * reason, resolved as a READ on that screen, in the policy context of the log so far with no
 * approval. Only Allow shows the screen. Any other verdict withholds it: a screen judgment never
 * runs anything, so a Confirm or a HandOff asks nobody and only withholds.
 *
 * Part of the runtime's fixed screen boundary, not of `toolPreExecute`: the interceptors judge the
 * calls the model made, and this is none of them. The decision is the PolicyEngine's alone.
 */
internal class ScreenGate(
    private val intents: IntentResolver,
    private val policy: PolicyEngine,
) {
    /** Whether [screen], read by [call], may be shown, judged on [records]. */
    fun judge(
        call: ToolCall,
        screen: ScreenSnapshot,
        records: List<SessionRecord>,
    ): ScreenJudgment {
        val observe = call.copy(name = SCREEN_JUDGMENT_TOOL, args = JsonObject(emptyMap()))
        val intent = intents.resolve(observe, RiskClass.READ, screen)
        val context = policyContextOf(records, call.taskId, screen, approved = null)
        return when (val verdict = policy.evaluate(observe, intent, context)) {
            Verdict.Allow -> ScreenJudgment.Shown
            is Verdict.Refuse -> ScreenJudgment.Withheld(verdict.reason, verdict.hintForModel)
            is Verdict.Confirm -> ScreenJudgment.Withheld(ScreenJudged.CONFIRM, hint = null)
            is Verdict.HandOff -> ScreenJudgment.Withheld(ScreenJudged.HAND_OFF, hint = null)
        }
    }
}

/** What [ScreenGate] decided for one screen. */
internal sealed interface ScreenJudgment {
    /** The model may see the screen. */
    data object Shown : ScreenJudgment

    /**
     * The model sees only [withheld] of the screen. [rule] is the refusing rule, or the kind of a
     * Confirm or HandOff verdict; [hint] is what the model is told, `null` for those two (the
     * runtime then says only that the screen is withheld).
     */
    data class Withheld(
        val rule: String,
        val hint: String?,
    ) : ScreenJudgment
}

/**
 * The copy of a withheld screen a tool gets: its id, surface, pkg and detail, the metadata the
 * model may see (policy-rules §6.4), flagged [ScreenFlag.WITHHELD], with no elements, OCR,
 * activity or other flags, an EMPTY tree and a fixed fingerprint (the real one digests the content).
 */
internal fun ScreenSnapshot.withheld(): ScreenSnapshot =
    copy(
        activity = null,
        elements = emptyList(),
        ocr = null,
        quality = TreeQuality.EMPTY,
        flags = setOf(ScreenFlag.WITHHELD),
        fingerprint = WITHHELD_FINGERPRINT,
    )

/**
 * The screens the model saw (policy-rules §6.4): each judged shown for its call's tool
 * (`screen/judged` TOOL, SHOWN) and named by the same call's `tool/result` as the screen it shows
 * ([ToolResult.SCREEN]). Not seen: a withheld screen, one the runtime read itself (S' of a
 * re-check, the look after a handoff or a resume), the read before a capture, a wait's screens
 * before its last. Derived from the log alone, so a resumed task gets the same set.
 */
internal fun seenScreens(records: List<SessionRecord>): Set<SnapshotId> {
    val shown =
        records
            .mapNotNull { it.event as? ScreenJudged }
            .filter { it.purpose == JudgmentPurpose.TOOL && it.outcome == JudgmentOutcome.SHOWN }
    val results = records.mapNotNull { it.event as? ToolResultRecorded }
    val named = results.mapTo(HashSet()) { it.callId to shownBy(it.result) }
    return shown.filter { (it.callId to it.snapshot.value) in named }.mapTo(HashSet()) { it.snapshot }
}

/**
 * The withheld judgment, in [callId], of the screen [result] shows ([ToolResult.SCREEN]); `null`
 * when it shows none or that screen was not withheld for the call.
 */
internal fun withheldShown(
    records: List<SessionRecord>,
    callId: ToolCallId,
    result: ToolResult,
): ScreenJudged? {
    val screen = shownBy(result) ?: return null
    return records.asReversed().firstNotNullOfOrNull { record ->
        (record.event as? ScreenJudged)?.takeIf {
            it.callId == callId && it.snapshot.value == screen && it.outcome == JudgmentOutcome.WITHHELD
        }
    }
}

/** The snapshot id [result] names as the screen it shows, as written; `null` when it names none. */
private fun shownBy(result: ToolResult): String? =
    (result.details?.get(ToolResult.SCREEN) as? JsonPrimitive)?.takeIf { it.isString }?.content

private const val WITHHELD_FINGERPRINT = "withheld"
