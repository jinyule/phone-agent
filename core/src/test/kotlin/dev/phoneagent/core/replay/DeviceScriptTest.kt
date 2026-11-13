package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.session.ApprovalAsked
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.JudgmentPurpose
import dev.phoneagent.core.session.PolicyVerdictRecorded
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionCodec
import dev.phoneagent.core.session.SessionEvent
import dev.phoneagent.core.session.SessionRecord
import dev.phoneagent.core.session.SessionSamples
import dev.phoneagent.core.session.ToolResultRecorded
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Where an event's data keeps the id of the call it belongs to (`replay/normalized-fields.txt`). */
private val OWN_CALL = listOf("callId", "card.callId", "call.id")

private fun field(
    data: JsonElement,
    path: String,
): String? =
    path
        .split('.')
        .fold(data as JsonElement?) { at, name -> (at as? JsonObject)?.get(name) }
        ?.jsonPrimitive
        ?.contentOrNull

class DeviceScriptTest {
    private val call = ReplaySamples.call
    private val other = ToolCallId("toolu_02")
    private val tap = UiAction.Tap(Target.Ref(ElementRef("e2")))

    private fun records(vararg events: SessionEvent): List<SessionRecord> =
        events.mapIndexed { i, e ->
            SessionRecord(i + 1L, 0, e)
        }

    private fun script(
        records: List<SessionRecord> = ReplaySamples.records,
        vararg facts: DeviceFact,
    ) = DeviceScript.of(records, ReplayOverride(device = facts.toList()))

    private fun verdict(id: ToolCallId) =
        PolicyVerdictRecorded(id, SecurityLevel.STANDARD, ActionIntent(IntentKind.NAVIGATE), Verdict.Allow)

    private fun result(id: ToolCallId) = ToolResultRecorded(id, "phone_tap", ToolResult(ToolStatus.OK, emptyList()))

    private fun acted(
        id: ToolCallId,
        outcome: ActionOutcome,
    ) = ScreenActed(id, ReplaySamples.snapshot.id, tap, outcome)

    private fun precheck(
        outcome: JudgmentOutcome = JudgmentOutcome.SHOWN,
        rule: String? = null,
    ) = ScreenJudged(call, ReplaySamples.snapshot.id, JudgmentPurpose.FRAME_PRECHECK, outcome, rule)

    /** A framed observe up to the judgment of the read after the capture: no frame kept. */
    private val dropped =
        arrayOf(
            verdict(call),
            ScreenObserved(call, ReplaySamples.snapshot),
            precheck(),
            ScreenObserved(call, ReplaySamples.checked),
            ScreenJudged(call, ReplaySamples.checked.id, JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN),
        )

    private fun failure(block: () -> Unit): String =
        assertThrows<IllegalArgumentException> { block() }.message.orEmpty()

    // OpenCall

    @Test
    fun `the open call is the latest one named that has no result`() {
        assertEquals(other, OpenCall.after(records(verdict(call), verdict(other))))
    }

    @Test
    fun `a result closes its call and the earlier open call is current again`() {
        assertEquals(call, OpenCall.after(records(verdict(call), verdict(other), result(other))))
    }

    @Test
    fun `an approval names its card's call`() {
        val card = ConfirmCard(call, ConfirmReason.OUTWARD, "send", "Chat")
        assertEquals(
            call,
            OpenCall.after(records(verdict(call), verdict(other), ApprovalAsked(PromptId("p-1"), card, expiresAt = 1))),
        )
    }

    @Test
    fun `no call is open when every call has its result`() {
        assertNull(OpenCall.after(records(verdict(call), result(call))))
    }

    @Test
    fun `every event that carries the id of its call names that call`() {
        val codec = SessionCodec()
        val wrong =
            SessionSamples.records.mapNotNull { record ->
                val line = Json.parseToJsonElement(codec.encode(record)).jsonObject
                val carried = OWN_CALL.firstNotNullOfOrNull { path -> field(line.getValue("data"), path) }
                val named = OpenCall.named(record.event)?.value
                val type = line.getValue("type").jsonPrimitive.content
                "$type carries $carried, OpenCall.named gives $named".takeIf {
                    carried != named && record.event !is ToolResultRecorded
                }
            }
        assertEquals(emptyList<String>(), wrong)
    }

    // DeviceScript

    @Test
    fun `the logged port calls of a call are its steps, a frame captured between its two reads`() {
        val steps = script().getValue(call)
        assertEquals(
            listOf(DevicePort.SNAPSHOT to 10L, DevicePort.FRAME to 14L, DevicePort.SNAPSHOT to 12L),
            steps.map { it.port to it.seq },
        )
        assertEquals(listOf(1, 2, 3), steps.map { it.op })
        assertEquals(DeviceResult.Snapshot(ReplaySamples.snapshot), steps[0].result)
        assertEquals(DeviceResult.Frame(ReplaySamples.blob, "image/png", 2, 2, som = true), steps[1].result)
        assertEquals(DeviceResult.Snapshot(ReplaySamples.checked), steps[2].result)
    }

    @Test
    fun `a capture the log kept no frame of gives none, at its place`() {
        val steps = script(records(*dropped)).getValue(call)
        assertEquals(
            listOf(DevicePort.SNAPSHOT to 2L, DevicePort.FRAME to 3L, DevicePort.SNAPSHOT to 4L),
            steps.map { it.port to it.seq },
        )
        assertEquals(DeviceResult.NoFrame, steps[1].result)
    }

    @Test
    fun `a read before a capture that was withheld makes no capture`() {
        val steps =
            script(
                records(
                    verdict(call),
                    ScreenObserved(call, ReplaySamples.snapshot),
                    precheck(JudgmentOutcome.WITHHELD, "x"),
                ),
            ).getValue(call)
        assertEquals(listOf(DevicePort.SNAPSHOT), steps.map { it.port })
    }

    @Test
    fun `only the judgment before a capture makes one`() {
        val tool = ScreenJudged(call, ReplaySamples.snapshot.id, JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN)
        val steps = script(records(verdict(call), ScreenObserved(call, ReplaySamples.snapshot), tool)).getValue(call)
        assertEquals(listOf(DevicePort.SNAPSHOT), steps.map { it.port })
    }

    @Test
    fun `a frame that follows no capture of its screen fails`() {
        val frame =
            ScreenFrame(
                ReplaySamples.blob,
                "image/png",
                2,
                2,
                som = true,
                snapshotId = ReplaySamples.checked.id,
                checkedOn = ReplaySamples.checked.id,
            )
        assertTrue("follows no capture of that screen" in failure { script(records(*dropped, frame)) })
    }

    @Test
    fun `a fact says what a capture without a kept frame gave`() {
        val thrown = Thrown(IllegalStateException::class.java.name, "x")
        val steps =
            script(records(*dropped), DeviceFact("toolu_01", 2, DevicePort.FRAME, throws = thrown, delayMs = 40))
                .getValue(call)
        assertEquals(DeviceResult.Throws(thrown) to 40L, steps[1].result to steps[1].delayMs)
        assertEquals(3, steps.size, "a capture is no extra port call")
    }

    @Test
    fun `a delay on a kept frame keeps the frame`() {
        val step = script(facts = arrayOf(DeviceFact("toolu_01", 2, DevicePort.FRAME, delayMs = 40))).getValue(call)[1]
        assertEquals(
            DeviceResult.Frame(ReplaySamples.blob, "image/png", 2, 2, som = true) to 40L,
            step.result to step.delayMs,
        )
    }

    @Test
    fun `a fact that says a kept frame gave none fails`() {
        assertTrue(
            "has its frame in the log, the fact says noFrame" in
                failure { script(facts = arrayOf(DeviceFact("toolu_01", 2, DevicePort.FRAME, noFrame = true))) },
        )
    }

    @Test
    fun `an unlogged fact takes its place among the logged port calls`() {
        val steps =
            script(
                facts = arrayOf(DeviceFact("toolu_01", 2, DevicePort.AWAIT_CHANGE, changed = false)),
            ).getValue(call)
        assertEquals(
            listOf(DevicePort.SNAPSHOT, DevicePort.AWAIT_CHANGE, DevicePort.FRAME, DevicePort.SNAPSHOT),
            steps.map { it.port },
        )
        assertEquals(listOf(1, 2, 3, 4), steps.map { it.op })
        assertEquals(DeviceResult.Changed(false), steps[1].result)
    }

    @Test
    fun `a note gives a logged port call its delay`() {
        val steps =
            script(
                facts = arrayOf(DeviceFact("toolu_01", 1, DevicePort.SNAPSHOT, delayMs = 250)),
            ).getValue(call)
        assertEquals(listOf(250L, 0L, 0L), steps.map { it.delayMs })
    }

    @Test
    fun `a fact for a call the log does not drive makes its own queue`() {
        val steps =
            script(
                facts =
                    arrayOf(
                        DeviceFact(
                            "toolu_09",
                            1,
                            DevicePort.SNAPSHOT,
                            throws = Thrown(IllegalStateException::class.java.name, "x"),
                        ),
                    ),
            )
        assertEquals(
            DeviceResult.Throws(Thrown(IllegalStateException::class.java.name, "x")),
            steps.getValue(ToolCallId("toolu_09")).single().result,
        )
    }

    @Test
    fun `an action cut off by a cancellation hangs`() {
        val unfinished =
            ActionOutcome(ActionOutcome.Status.Failed(DeviceScript.UNFINISHED), ActionOutcome.Effect.UNKNOWN)
        val step = script(records(verdict(call), acted(call, unfinished))).getValue(call).single()
        assertEquals(DeviceResult.Hangs, step.result)
        assertEquals(tap to ReplaySamples.snapshot.id, step.action to step.on)
    }

    @Test
    fun `an action that threw throws the exception its fact gives`() {
        val threw =
            ActionOutcome(ActionOutcome.Status.Failed("threw java.io.IOException"), ActionOutcome.Effect.UNKNOWN)
        val thrown = Thrown("java.io.IOException", "helper gone")
        val step =
            script(
                records(verdict(call), acted(call, threw)),
                DeviceFact("toolu_01", 1, DevicePort.ACT, throws = thrown),
            ).getValue(call).single()
        assertEquals(DeviceResult.Throws(thrown), step.result)
    }

    @Test
    fun `an action that threw without its fact fails`() {
        val threw =
            ActionOutcome(ActionOutcome.Status.Failed("threw java.io.IOException"), ActionOutcome.Effect.UNKNOWN)
        assertTrue("must give its message" in failure { script(records(verdict(call), acted(call, threw))) })
    }

    @Test
    fun `a fact naming another exception class than the log fails`() {
        val threw =
            ActionOutcome(ActionOutcome.Status.Failed("threw java.io.IOException"), ActionOutcome.Effect.UNKNOWN)
        val fact =
            DeviceFact("toolu_01", 1, DevicePort.ACT, throws = Thrown(IllegalStateException::class.java.name, "x"))
        assertTrue(
            "the fact says java.lang.IllegalStateException" in
                failure { script(records(verdict(call), acted(call, threw)), fact) },
        )
    }

    @Test
    fun `a fact that gives an exception to an action that returned fails`() {
        val ok = ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED)
        val fact = DeviceFact("toolu_01", 1, DevicePort.ACT, throws = Thrown("java.io.IOException", "x"))
        assertTrue("did not throw" in failure { script(records(verdict(call), acted(call, ok)), fact) })
    }

    @Test
    fun `a fact beyond its call's port calls fails`() {
        assertTrue(
            "facts name ops [5]" in
                failure { script(facts = arrayOf(DeviceFact("toolu_01", 5, DevicePort.SNAPSHOT, delayMs = 1))) },
        )
    }

    @Test
    fun `a note on a port call of another method fails`() {
        assertTrue(
            "is a SNAPSHOT, the fact says FRAME" in
                failure { script(facts = arrayOf(DeviceFact("toolu_01", 1, DevicePort.FRAME, delayMs = 1))) },
        )
    }

    @Test
    fun `a read for no call while a call is open fails`() {
        assertTrue(
            "replay cannot place it" in
                failure { script(records(verdict(call), ScreenObserved(null, ReplaySamples.snapshot))) },
        )
    }
}
