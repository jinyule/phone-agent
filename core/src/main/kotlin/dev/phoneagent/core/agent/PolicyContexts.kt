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
import dev.phoneagent.core.session.ToolExecuted
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.session.ToolStarted
import dev.phoneagent.core.tool.ToolStatus

/**
 * The screen the policy judges a call on (`PolicyContext.screen`, contracts addendum §14): the
 * newest snapshot recorded by a tool call whose `tool/result` is already in the log (an
 * observation, the screen read back after an action or launch, or the runtime's own read for that
 * call: the re-check after its approval, the look after its handoff). Snapshots of a call still
 * running, and any without a call, do not count. `null` before any screen counts, and after a
 * handoff until a screen is recorded after it: from its `handoff/started` on, whether it ends
 * normally, is stopped, times out or breaks, every screen before it is hidden, since the user may
 * have changed what is shown; a `handoff/end` hides those recorded during the handoff too.
 *
 * This screen can be newer than the one the model saw: a call's result need not show the screen it
 * recorded (an action's read-back, for one). That is deliberate. Judging on the newer screen costs
 * at most one extra stale refusal for a call by ref (its snapshot id no longer matches); judging
 * strictly on the screen the model saw would resolve a coordinate tap against an outdated screen
 * and could let through a tap that the current screen needs confirmed.
 */
internal fun latestSnapshot(records: List<SessionRecord>): ScreenSnapshot? {
    val answered = records.mapNotNullTo(HashSet()) { (it.event as? ToolResultRecorded)?.callId }
    val seen = records.asReversed().firstNotNullOfOrNull { seenSnapshot(it.event, answered) }
    return (seen as? Seen.Screen)?.snapshot
}

/**
 * Whether [callId] handed off and no screen was recorded for it after its last handoff began or
 * ended; a handoff that never ended (stopped, timed out, broken, cut by the process's death)
 * counts as lost too.
 */
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
            (event is HandoffStarted && event.promptId in handoffs) ||
                (event is HandoffEnded && event.promptId in handoffs) ||
                seenSnapshot(event, setOf(callId)) is Seen.Screen
        }
    return last != null && seenSnapshot(last.event, setOf(callId)) !is Seen.Screen
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

        event is HandoffStarted || event is HandoffEnded -> {
            Seen.Hidden
        }

        else -> {
            null
        }
    }

/**
 * The policy's view of the task, derived from the log only (so replay rebuilds it). Both package
 * sets follow the execution facts, never a call's final result (an interceptor or a hand-off can
 * make that OK without the tool running):
 * - `operatedPackages`: the `intent.pkg` of every call that has a `tool/started`, for a non-READ
 *   intent of the verdict it started on (`verdictSeq`; a launch resolves to its target package);
 * - `outwardConfirmedPackages`: for every call that started and whose `tool/executed` is OK, the
 *   pkg of the last card approved for it before that verdict, the approval it ran on, when that
 *   card is OUTWARD and its pkg is the started intent's. An approval alone, a card replaced by a
 *   later confirmation (another App, another reason), or a failed send qualifies nothing.
 */
internal fun policyContextOf(
    records: List<SessionRecord>,
    taskId: TaskId,
    screen: ScreenSnapshot?,
    approved: ConfirmCard?,
): PolicyContext {
    val bySeq = records.associateBy { it.seq }
    // The log's invariants make verdictSeq the seq of this call's Allow (SessionInvariants).
    val ran =
        records.mapNotNull { it.event as? ToolStarted }.map {
            it to
                bySeq.getValue(it.verdictSeq).event as PolicyVerdictRecorded
        }
    val operated =
        ran
            .map { (_, verdict) -> verdict.intent }
            .filter { it.kind != IntentKind.READ }
            .mapNotNullTo(HashSet()) { it.pkg }
    val sent =
        records.mapNotNullTo(HashSet()) {
            (it.event as? ToolExecuted)
                ?.takeIf { e ->
                    e.status == ToolStatus.OK
                }?.callId
        }
    val approvals = approvalsOf(records)
    val outward =
        ran
            .filter { (started, _) -> started.callId in sent }
            .mapNotNullTo(HashSet()) { (started, verdict) ->
                approvals
                    .lastOrNull { (seq, card) -> seq < started.verdictSeq && card.callId == started.callId }
                    ?.let { (_, card) ->
                        card.takeIf {
                            it.reason == ConfirmReason.OUTWARD &&
                                it.pkg == verdict.intent.pkg
                        }
                    }?.pkg
            }
    return PolicyContext(taskId, levelOf(records), settingsOf(records).unattended, screen, operated, outward, approved)
}

/** Every approved card with the seq of its `approval/decided`, in log order. */
private fun approvalsOf(records: List<SessionRecord>): List<Pair<Long, ConfirmCard>> {
    val cards = records.mapNotNull { it.event as? ApprovalAsked }.associate { it.promptId to it.card }
    return records.mapNotNull { record ->
        (record.event as? ApprovalDecided)
            ?.takeIf { it.result == ConfirmResult.APPROVED }
            ?.let { cards[it.promptId] }
            ?.let { record.seq to it }
    }
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
