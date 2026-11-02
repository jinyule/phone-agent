package dev.phoneagent.core.agent

import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.PolicyContext
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.ApprovalDecided
import dev.phoneagent.core.session.HandoffEnded
import dev.phoneagent.core.session.HandoffStarted
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.TaskLevelChanged
import dev.phoneagent.core.session.TaskStarted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.ToolStatus

/**
 * The screen the policy judges a call on (contracts addendum §14): the newest snapshot recorded by
 * a tool call whose `tool/result` is already in the log (an observation, the screen read back after
 * an action or launch, or the runtime's look after that call's handoff). Runtime observations
 * without a call (the re-check after approval) and those of a call still running do not count.
 * `null` before any screen counts, and after a handoff whose screen could not be read: a
 * `handoff/end` hides every screen before it, since the user may have changed what is shown.
 */
internal fun latestSnapshot(records: List<SessionRecord>): ScreenSnapshot? {
    val answered = records.mapNotNullTo(HashSet()) { (it.event as? ToolResultRecorded)?.callId }
    val seen = records.asReversed().firstNotNullOfOrNull { seenSnapshot(it.event, answered) }
    return (seen as? Seen.Screen)?.snapshot
}

/** Whether [callId] handed off and no screen was recorded for it after its last handoff ended. */
internal fun screenLostAfterHandoff(
    records: List<SessionRecord>,
    callId: ToolCallId,
): Boolean {
    val handoffs =
        records
            .mapNotNull { it.event as? HandoffStarted }
            .filter { it.callId == callId }
            .mapTo(HashSet()) { it.promptId }
    val last =
        records.lastOrNull { record ->
            val event = record.event
            (event is HandoffEnded && event.promptId in handoffs) || seenSnapshot(event, setOf(callId)) is Seen.Screen
        }
    return last?.event is HandoffEnded
}

private sealed interface Seen {
    class Screen(
        val snapshot: ScreenSnapshot,
    ) : Seen

    data object Hidden : Seen
}

private fun seenSnapshot(
    event: SessionEvent,
    answered: Set<ToolCallId?>,
): Seen? =
    when {
        event is ScreenObserved -> {
            event.snapshot.takeIf { event.callId in answered }?.let(Seen::Screen)
        }

        event is ScreenActed -> {
            event.outcome.after
                .takeIf { event.callId in answered }
                ?.let(Seen::Screen)
        }

        event is ScreenLaunched -> {
            event.outcome.after
                .takeIf { event.callId in answered }
                ?.let(Seen::Screen)
        }

        event is HandoffEnded -> {
            Seen.Hidden
        }

        else -> {
            null
        }
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
    return PolicyContext(taskId, levelOf(records), settingsOf(records).unattended, screen, operated, outward, approved)
}

/** The task's starting settings: `task/start`, always record 1 of a log. */
internal fun settingsOf(records: List<SessionRecord>): TaskStarted =
    when (val first = records.first().event) {
        is TaskStarted -> first
        else -> error("a session log starts with task/start, not $first")
    }

/** The security level in force after [records]: the last `task/level-changed`, else `task/start`'s. */
internal fun levelOf(records: List<SessionRecord>): SecurityLevel =
    records.asReversed().firstNotNullOfOrNull { (it.event as? TaskLevelChanged)?.level } ?: settingsOf(records).level
