package dev.phoneagent.core.session

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.JsonObject

/**
 * Samples of the execution facts `PolicyContext` is derived from (its KDoc): small logs and the
 * `operatedPackages` and `outwardConfirmedPackages` a runtime derives once the log ends there.
 * WP02 derives both sets from every [cases] entry and compares; `PolicyContextSourcesTest` keeps
 * the samples consistent with the documented rules.
 */
object PolicyContextSamples {
    /** One situation; the expected sets hold after the last record. */
    data class Case(
        val name: String,
        val records: List<SessionRecord>,
        val operatedPackages: Set<String>,
        val outwardConfirmedPackages: Set<String>,
    )

    const val CHAT = "com.tencent.mm"
    const val NOTES = "com.example.notes"

    /** Only ever read: a derivation that counts READ calls would list it. */
    const val NEWS = "com.example.news"

    /** Where a re-check moved the send to. */
    const val MAIL = "com.example.mail"

    /** A log under construction; [add] returns the seq it gave. Record 1 is `task/start`. */
    private class Log {
        val records = mutableListOf(SessionRecord(1, 0, SessionSamples.start))
        private var prompts = 0

        fun add(
            event: SessionEvent,
            vararg sources: Long,
        ): Long {
            val seq = records.size + 1L
            records += SessionRecord(seq, seq * 10, event, sources = sources.toList())
            return seq
        }

        fun call(id: String): Long =
            add(
                ToolCallRecorded(
                    ToolCall(
                        ToolCallId(id),
                        SessionSamples.task,
                        SessionSamples.session,
                        "phone_tap",
                        JsonObject(emptyMap()),
                        "r",
                    ),
                ),
            )

        fun verdict(
            id: String,
            intent: ActionIntent,
            verdict: Verdict,
        ): Long = add(PolicyVerdictRecorded(ToolCallId(id), SecurityLevel.STANDARD, intent, verdict))

        /** Asks [card], records [result], and for an approval the fresh screen of the re-check. */
        fun decide(
            card: ConfirmCard,
            result: ConfirmResult,
        ) {
            val prompt = PromptId("prompt-${++prompts}")
            add(ApprovalAsked(prompt, card, expiresAt = 900_000))
            add(ApprovalDecided(prompt, result))
            if (result == ConfirmResult.APPROVED) add(ScreenObserved(card.callId, SessionSamples.snapshot))
        }

        fun result(
            id: String,
            status: ToolStatus,
        ): Long = add(ToolResultRecorded(ToolCallId(id), "phone_tap", ToolResult(status, listOf(Part.Text("x")))))

        /** A call that ran on [verdictSeq] and returned [status], with its result. */
        fun run(
            id: String,
            verdictSeq: Long,
            status: ToolStatus,
        ) {
            add(ToolStarted(ToolCallId(id), verdictSeq))
            add(ToolExecuted(ToolCallId(id), status))
            result(id, status)
        }
    }

    private val send = ActionIntent(IntentKind.OUTWARD, CHAT)
    private val sendMail = ActionIntent(IntentKind.OUTWARD, MAIL)
    private val pay = ActionIntent(IntentKind.SPEND, CHAT)
    private val type = ActionIntent(IntentKind.INPUT, NOTES)
    private val look = ActionIntent(IntentKind.READ, NEWS)

    private fun sendCard(
        id: String,
        pkg: String = CHAT,
    ) = ConfirmCard(ToolCallId(id), ConfirmReason.OUTWARD, "发送消息", "张三", pkg = pkg)

    private fun payCard(id: String) =
        ConfirmCard(ToolCallId(id), ConfirmReason.SPEND, "付款", "张三", amount = Money(1_000, "CNY"), pkg = CHAT)

    /** A Confirm of [card] on [intent], asked and approved: one round of a call's checks. */
    private fun Log.approvedRound(
        intent: ActionIntent,
        card: ConfirmCard,
    ) {
        verdict(card.callId.value, intent, Verdict.Confirm(card))
        decide(card, ConfirmResult.APPROVED)
    }

    /** An OUTWARD card for [id], approved, and the re-check's verdict on the fresh screen. */
    private fun Log.approvedSend(
        id: String,
        recheck: Verdict,
    ): Long {
        call(id)
        verdict(id, send, Verdict.Confirm(sendCard(id)))
        decide(sendCard(id), ConfirmResult.APPROVED)
        return verdict(id, send, recheck)
    }

    private fun case(
        name: String,
        operated: Set<String>,
        outward: Set<String>,
        build: Log.() -> Unit,
    ) = Case(name, Log().apply(build).records.toList(), operated, outward)

    /** Every case. */
    val cases: List<Case> =
        listOf(
            case("refused", emptySet(), emptySet()) {
                call("c1")
                verdict("c1", send, Verdict.Refuse("hard-deny", "Not allowed."))
                result("c1", ToolStatus.REFUSED)
            },
            case("approved-then-refused-on-recheck", emptySet(), emptySet()) {
                approvedSend("c1", Verdict.Refuse("changed", "The screen changed."))
                result("c1", ToolStatus.REFUSED)
            },
            case("handed-off-and-done-by-the-user", emptySet(), emptySet()) {
                call("c1")
                verdict("c1", type, Verdict.HandOff(HandoffKind.PASSWORD, "enter the password"))
                val prompt = PromptId("prompt-handoff")
                add(HandoffStarted(prompt, HandoffKind.PASSWORD, "请输入密码", ToolCallId("c1")))
                add(HandoffEnded(prompt, HandoffResult.DONE))
                result("c1", ToolStatus.OK)
            },
            case("approved-and-allowed-but-answered-by-an-interceptor", emptySet(), emptySet()) {
                approvedSend("c1", Verdict.Allow)
                result("c1", ToolStatus.OK)
            },
            case("approved-started-and-failed", setOf(CHAT), emptySet()) {
                run("c1", approvedSend("c1", Verdict.Allow), ToolStatus.ERROR)
            },
            case("approved-started-and-timed-out", setOf(CHAT), emptySet()) {
                add(ToolStarted(ToolCallId("c1"), approvedSend("c1", Verdict.Allow)))
                result("c1", ToolStatus.TIMEOUT)
            },
            case("approved-and-executed", setOf(CHAT, NOTES), setOf(CHAT)) {
                run("c1", approvedSend("c1", Verdict.Allow), ToolStatus.OK)
                call("c2")
                run("c2", verdict("c2", look, Verdict.Allow), ToolStatus.OK)
                call("c3")
                run("c3", verdict("c3", type, Verdict.Allow), ToolStatus.OK)
            },
            case("approval-of-one-call-execution-of-another", setOf(CHAT), emptySet()) {
                approvedSend("c1", Verdict.Refuse("changed", "The screen changed."))
                result("c1", ToolStatus.REFUSED)
                call("c2")
                run("c2", verdict("c2", send, Verdict.Allow), ToolStatus.OK)
            },
            case("re-approved-after-the-send-moved-to-another-app", setOf(MAIL), setOf(MAIL)) {
                call("c1")
                approvedRound(send, sendCard("c1"))
                approvedRound(sendMail, sendCard("c1", MAIL))
                run("c1", verdict("c1", sendMail, Verdict.Allow), ToolStatus.OK)
            },
            case("outward-approval-superseded-by-a-spend-approval", setOf(CHAT), emptySet()) {
                call("c1")
                approvedRound(send, sendCard("c1"))
                approvedRound(pay, payCard("c1"))
                run("c1", verdict("c1", pay, Verdict.Allow), ToolStatus.OK)
            },
            case("approved-payment-executed", setOf(CHAT), emptySet()) {
                call("c1")
                approvedRound(pay, payCard("c1"))
                run("c1", verdict("c1", pay, Verdict.Allow), ToolStatus.OK)
            },
            case("approved-card-names-another-app-than-the-execution", setOf(MAIL), emptySet()) {
                call("c1")
                approvedRound(send, sendCard("c1"))
                run("c1", verdict("c1", sendMail, Verdict.Allow), ToolStatus.OK)
            },
            case("model-requested-confirmation", emptySet(), emptySet()) {
                call("c1")
                val card = ConfirmCard(ToolCallId("c1"), ConfirmReason.REQUESTED_BY_MODEL, "确认要继续吗")
                verdict("c1", ActionIntent(IntentKind.READ), Verdict.Confirm(card))
                decide(card, ConfirmResult.APPROVED)
                run("c1", verdict("c1", ActionIntent(IntentKind.READ), Verdict.Allow), ToolStatus.OK)
            },
        )
}
