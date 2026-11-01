package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.ToolStatus

/**
 * The screen the model saw last (contracts addendum §14): the newest snapshot recorded by a tool
 * call whose `tool/result` is already in the log (an observation, or the screen read back after an
 * action or launch). Runtime observations (`callId == null`: after resume, handoff or approval)
 * and those of a call still running are not shown to the model, so they do not count. `null`
 * before the model saw any screen.
 */
internal fun latestSnapshot(records: List<SessionRecord>): ScreenSnapshot? {
    val answered = records.mapNotNullTo(HashSet()) { (it.event as? ToolResultRecorded)?.callId }
    return records.asReversed().firstNotNullOfOrNull { seenSnapshot(it.event, answered) }
}

private fun seenSnapshot(
    event: SessionEvent,
    answered: Set<ToolCallId>,
): ScreenSnapshot? =
    when {
        event is ScreenObserved -> event.snapshot.takeIf { event.callId in answered }
        event is ScreenActed -> event.outcome.after.takeIf { event.callId in answered }
        event is ScreenLaunched -> event.outcome.after.takeIf { event.callId in answered }
        else -> null
    }

/**
 * The policy's view of the task, derived from the log only (so replay rebuilds it):
 * - `operatedPackages`: the `intent.pkg` of every non-READ call whose `tool/result` is OK (the
 *   last verdict of a call counts; launches resolve to their target package);
 * - `outwardConfirmedPackages`: the `card.pkg` of every APPROVED card whose reason is OUTWARD.
 */
internal fun policyContextOf(
    records: List<SessionRecord>,
    taskId: TaskId,
    config: LoopConfig,
    screen: ScreenSnapshot?,
    approved: ConfirmCard?,
): PolicyContext {
    val succeeded =
        records.mapNotNullTo(HashSet()) { r ->
            (r.event as? ToolResultRecorded)?.takeIf { it.result.status == ToolStatus.OK }?.callId
        }
    val intents = records.mapNotNull { it.event as? PolicyVerdictRecorded }.associate { it.callId to it.intent }
    val operated =
        intents
            .filter { (callId, intent) -> callId in succeeded && intent.kind != IntentKind.READ }
            .values
            .mapNotNullTo(HashSet()) { it.pkg }
    val cards = records.mapNotNull { it.event as? ApprovalAsked }.associate { it.promptId to it.card }
    val outward =
        records
            .mapNotNull { (it.event as? ApprovalDecided)?.takeIf { d -> d.result == ConfirmResult.APPROVED } }
            .mapNotNull { cards[it.promptId]?.takeIf { card -> card.reason == ConfirmReason.OUTWARD }?.pkg }
            .toSet()
    return PolicyContext(taskId, config.level, config.unattended, screen, operated, outward, approved)
}
