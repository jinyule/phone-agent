package dev.phoneagent.core.session

import dev.phoneagent.core.ids.BlobRef
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.PromptId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.ids.SessionId
import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ids.TaskId
import dev.phoneagent.core.ids.ToolCallId
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.ContextEditPolicy
import dev.phoneagent.core.model.CredentialKind
import dev.phoneagent.core.model.ImageMode
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ReasoningKind
import dev.phoneagent.core.model.ReasoningLevel
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.Usage
import dev.phoneagent.core.model.WireProfile
import dev.phoneagent.core.model.WireSetting
import dev.phoneagent.core.policy.ActionIntent
import dev.phoneagent.core.policy.FieldClass
import dev.phoneagent.core.policy.IntentKind
import dev.phoneagent.core.policy.SecurityLevel
import dev.phoneagent.core.policy.Verdict
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ConfirmCard
import dev.phoneagent.core.ports.ConfirmReason
import dev.phoneagent.core.ports.ConfirmResult
import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.HandoffKind
import dev.phoneagent.core.ports.HandoffResult
import dev.phoneagent.core.ports.LaunchTarget
import dev.phoneagent.core.ports.Money
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.Question
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.SystemIntentKind
import dev.phoneagent.core.ports.SystemKey
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import dev.phoneagent.core.tool.EndReason
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolControl
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One record of every event type, with every nested variant; ids and times are fixed. */
object SessionSamples {
    val task = TaskId("task-1")
    val session = SessionId("session-1")
    val provider = ProviderId("anthropic")
    val model = ModelId("claude-opus-5-5")
    val call = ToolCallId("call-1")
    val prompt = PromptId("prompt-1")
    val blob = BlobRef("a".repeat(64))
    val header = SessionHeader(version = 1, sessionId = session, taskId = task, createdAt = 1_000)
    val start = TaskStarted(SecurityLevel.STANDARD, unattended = false)

    /** A wire snapshot with every field set away from its default. */
    val wire =
        WireProfile(
            mappingVersion = 1,
            credential = CredentialKind.API_KEY,
            headers = mapOf("anthropic-beta" to "context-management-2025-06-27"),
            images = ImageMode.PLACEHOLDER,
            reasoningField = "reasoning_content",
            echoReasoning = true,
            reasoningEffort = WireSetting("output_config.effort", "high"),
            maxTokensField = "max_tokens",
            disableParallelToolCalls = true,
            streamOptions = true,
            includeEncryptedReasoning = true,
            recoverLeakedToolCalls = true,
            options = mapOf("tool_choice" to "auto"),
        )

    val image = Part.Image(blob, "image/jpeg", 720, 1280)
    val reasoning =
        Part.Reasoning(
            provider,
            model,
            ReasoningKind.ANTHROPIC_THINKING,
            buildJsonObject {
                put("type", "thinking")
                put("signature", "sig")
            },
            summary = "looked at the screen",
        )
    val toolCallPart = Part.ToolCall(call, "phone_tap", """{"screen":"s1","ref":"e2","reason":"open chat"}""")

    val schema =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("reason") { put("type", "string") } }
        }

    private val element =
        UiElement(
            ref = ElementRef("e2"),
            role = ElementRole.BUTTON,
            name = "发送",
            bounds = Bounds(900, 940, 960, 970),
            states = setOf(ElementState.CLICKABLE),
            parent = ElementRef("e1"),
            window = 3,
            resourceId = "send",
        )
    private val field =
        UiElement(ElementRef("e3"), ElementRole.INPUT, hint = "密码", bounds = Bounds(0, 0, 999, 100), secret = true)
    val snapshot =
        ScreenSnapshot(
            id = SnapshotId("s1"),
            surface = SurfaceRef(7, SurfaceKind.AGENT),
            pkg = "com.example.chat",
            activity = ".Main",
            elements = listOf(element, field),
            ocr = listOf(OcrLine("10:00", Bounds(0, 0, 50, 20), 0.9)),
            quality = TreeQuality.GOOD,
            flags = setOf(ScreenFlag.KEYBOARD),
            fingerprint = "fp-1",
        )
    private val toolCall =
        ToolCall(call, task, session, "phone_tap", buildJsonObject { put("ref", "e2") }, reason = "打开聊天")
    private val card =
        ConfirmCard(call, ConfirmReason.SPEND, "pay", "Shop", amount = Money(1999, "CNY"), pkg = "com.example.pay")

    private val replyWithCall =
        AssistantMessage(
            attempt = 3,
            provider = provider,
            model = model,
            parts = listOf(reasoning, Part.Text("点发送"), toolCallPart),
            stopReason = StopReason.TOOL_USE,
            usage = Usage(1000, 50, cacheReadTokens = 800, cacheWriteTokens = 100, reasoningTokens = 20),
            providerMetadata = buildJsonObject { put("id", "msg_1") },
        )
    private val sideCall =
        SideRequest(
            ModelRole.VISION,
            provider,
            model,
            ModelProtocol.ANTHROPIC_MESSAGES,
            ChatRequest(
                system = listOf(PromptBlock("vision", "Describe the screen.")),
                tools = emptyList(),
                messages = listOf(Message(Role.USER, listOf(image))),
                wire = WireProfile(mappingVersion = 1, credential = CredentialKind.NONE),
                cache = CacheLayout.NONE,
                maxOutputTokens = 256,
            ),
        )

    private val allowed =
        PolicyVerdictRecorded(
            call,
            SecurityLevel.STANDARD,
            ActionIntent(IntentKind.OUTWARD, "com.example.chat", element),
            Verdict.Allow,
        )

    private val beforeStart: List<SessionEvent> =
        listOf(
            start,
            TaskLevelChanged(SecurityLevel.CAUTIOUS),
            TurnStarted(1),
            InboxSpliced(
                InboxQueue.NEXT_TURN,
                removed = 0,
                added = listOf(InboxEntry(InboxEntryKind.FOLLOWUP, listOf(Part.Text("给张三发消息"), image))),
            ),
            InboxSpliced(
                InboxQueue.NEXT_STEP,
                removed = 1,
                added = listOf(InboxEntry(InboxEntryKind.INJECT, listOf(Part.Text("timer")), "timer")),
            ),
            UserMessage(UserMessageKind.USER, listOf(Part.Text("给张三发消息"), image)),
            UserMessage(UserMessageKind.RUNTIME_CONTEXT, listOf(Part.Text("foreground: launcher"))),
            UserMessage(UserMessageKind.INJECTED, listOf(Part.Text("timer fired")), source = "timer"),
            StepStarted(1, 1),
            RequestHeader(
                provider = provider,
                model = model,
                protocol = ModelProtocol.ANTHROPIC_MESSAGES,
                wire = wire,
                system = listOf(PromptBlock("identity", "You operate a phone.")),
                tools = listOf(ToolDef("phone_tap", "Tap an element.", schema)),
                cache = CacheLayout.DEFAULT,
                contextEdits =
                    ContextEditPolicy.ServerSide(
                        clearToolUsesAboveTokens = 100_000,
                        keepToolUses = 3,
                        compactAboveTokens = 150_000,
                        clearAtLeastTokens = 20_000,
                        compactInstructions = "Summarize the task so far.",
                    ),
                maxOutputTokens = 64_000,
                reasoning = ReasoningLevel.HIGH,
            ),
            sideCall,
            SideResponse(SideOutcome.Completed(ChatResponse(listOf(Part.Text("a chat screen")), StopReason.END_TURN))),
            sideCall,
            SideResponse(
                SideOutcome.Failed(ModelErrorKind.SERVER, "server error 503", httpStatus = 503, retryAfterMs = 0),
            ),
            AssistantAttempt(1, ModelErrorKind.OVERLOADED, "provider overloaded", httpStatus = 529, retryAfterMs = 500),
            RequestRetry(2, 500, ModelErrorKind.OVERLOADED),
            AssistantMessage(2, provider, model, listOf(Part.Text("我先看看")), stopReason = null, interrupted = true),
            RequestRetry(3, 1000, ModelErrorKind.NETWORK),
            replyWithCall,
            ToolCallRecorded(toolCall),
            PolicyVerdictRecorded(
                call,
                SecurityLevel.CAUTIOUS,
                ActionIntent(IntentKind.SPEND, amount = Money(1999)),
                Verdict.Confirm(card),
            ),
            PolicyVerdictRecorded(
                call,
                SecurityLevel.AUTONOMOUS,
                ActionIntent(IntentKind.INPUT, target = field, fieldClass = FieldClass.PASSWORD),
                Verdict.HandOff(HandoffKind.PASSWORD, "enter the password"),
            ),
            PolicyVerdictRecorded(
                call,
                SecurityLevel.STANDARD,
                ActionIntent(IntentKind.SETTINGS),
                Verdict.Refuse("hard-deny", "Not allowed."),
            ),
            allowed,
        )

    /** From the start of the call on its Allow ([allowed], the last record of [beforeStart]). */
    private val afterStart: List<SessionEvent> =
        listOf(
            ApprovalAsked(prompt, card, expiresAt = 901_000),
            ApprovalDecided(prompt, ConfirmResult.APPROVED),
            QuestionAsked(prompt, call, Question("哪个张三？", listOf("同事", "同学"))),
            QuestionAnswered(prompt, AskResult.Answered("同事")),
            QuestionAnswered(prompt, AskResult.Declined),
            QuestionAnswered(prompt, AskResult.Expired),
            QuestionAnswered(prompt, AskResult.Unavailable),
            HandoffStarted(prompt, HandoffKind.LOGIN, "请登录", call),
            HandoffEnded(prompt, HandoffResult.DONE),
            HandoffEnded(prompt, HandoffResult.FAILED, detail = "moveTaskToDisplay failed"),
            PromptWithdrawn(prompt),
            ScreenObserved(call, snapshot),
            ScreenObserved(null, snapshot.copy(ocr = null, pkg = null, activity = null)),
            ScreenActed(
                call,
                SnapshotId("s1"),
                UiAction.Tap(Target.Ref(ElementRef("e2"))),
                ActionOutcome(
                    ActionOutcome.Status.Ok,
                    ActionOutcome.Effect.CHANGED,
                    after = snapshot,
                    diff =
                        ScreenDiff(
                            base = SnapshotId("s1"),
                            added = listOf(element),
                            removed = listOf(ElementRef("e9")),
                            changed = listOf(field),
                            ocrAdded = listOf(OcrLine("10:00", Bounds(0, 0, 50, 20), 0.9)),
                            ocrRemoved = listOf(OcrLine("09:59", Bounds(0, 0, 50, 20), 0.8)),
                        ),
                ),
            ),
            // Read without OCR after a read with OCR lines: those lines are unverified, not removed.
            ScreenActed(
                call,
                SnapshotId("s1"),
                UiAction.Scroll(null, Direction.UP),
                ActionOutcome(
                    ActionOutcome.Status.Ok,
                    ActionOutcome.Effect.CHANGED,
                    after = snapshot.copy(id = SnapshotId("s2"), ocr = null, fingerprint = "fp-2"),
                    diff = ScreenDiff(base = SnapshotId("s1"), changed = listOf(field), ocrUnverified = true),
                ),
            ),
            ScreenActed(
                call,
                null,
                UiAction.Type(Target.Point(480, 955), "十点", append = true, submit = true),
                ActionOutcome(ActionOutcome.Status.Failed("not editable"), ActionOutcome.Effect.NO_CHANGE),
            ),
            ScreenActed(
                call,
                null,
                UiAction.Scroll(null, Direction.DOWN),
                ActionOutcome(
                    ActionOutcome.Status.StaleRef(ActionOutcome.StaleReason.ROW_CHANGED),
                    ActionOutcome.Effect.UNKNOWN,
                ),
            ),
            ScreenActed(
                call,
                null,
                UiAction.Swipe(Target.Point(500, 800), Target.Point(500, 200), 300),
                ActionOutcome(ActionOutcome.Status.Refused("own app"), ActionOutcome.Effect.SPILLED_BACK),
            ),
            ScreenActed(
                call,
                null,
                UiAction.Key(SystemKey.BACK),
                ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.RATE_LIMITED),
            ),
            ScreenActed(
                call,
                null,
                UiAction.SetWheel(Target.Ref(ElementRef("e4")), "10"),
                ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.SECURE_PAGE),
            ),
            ScreenLaunched(
                call,
                LaunchTarget.App("com.example.chat", cold = true),
                ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.NEW_APP),
            ),
            ScreenLaunched(
                call,
                LaunchTarget.Link("https://example.com/a"),
                ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED),
            ),
            ScreenLaunched(
                call,
                LaunchTarget.SystemIntent(SystemIntentKind.TIMER, mapOf("seconds" to "60")),
                ActionOutcome(ActionOutcome.Status.Ok, ActionOutcome.Effect.CHANGED),
            ),
            ScreenFrame(blob, "image/jpeg", 720, 1280, som = true, SnapshotId("s1"), checkedOn = SnapshotId("s2")),
            ScreenJudged(call, SnapshotId("s1"), JudgmentPurpose.TOOL, JudgmentOutcome.SHOWN),
            ScreenJudged(
                call,
                SnapshotId("s2"),
                JudgmentPurpose.FRAME_PRECHECK,
                JudgmentOutcome.WITHHELD,
                rule = "hard.finance_app",
                hint = "this app is off limits",
            ),
            ScreenJudged(call, SnapshotId("s2"), JudgmentPurpose.TOOL, JudgmentOutcome.WITHHELD, ScreenJudged.HAND_OFF),
            ToolExecuted(call, ToolStatus.OK),
            ToolResultRecorded(
                call,
                "phone_tap",
                ToolResult(
                    ToolStatus.OK,
                    listOf(
                        Part.Text("done"),
                        image,
                    ),
                    buildJsonObject {
                        put("effect", "CHANGED")
                    },
                ),
            ),
            ToolResultRecorded(
                ToolCallId("call-3"),
                "end_not_done",
                ToolResult(
                    ToolStatus.OK,
                    listOf(Part.Text("ended")),
                    control = ToolControl.EndTurn(EndReason.BLOCKED, "需要登录，已交给用户"),
                ),
            ),
            ToolResultRecorded(
                ToolCallId("call-2"),
                "phone_tap",
                ToolResult.failure(ToolStatus.SKIPPED, "only one action per step"),
            ),
            StepEnded(1, 1, StepOutcome.COMPLETED),
        )

    private val head: List<SessionEvent> = beforeStart + ToolStarted(call, beforeStart.size.toLong()) + afterStart

    private val tail: List<SessionEvent> =
        listOf(
            CompactionStarted(CompactionTrigger.PRESSURE, 120_000),
            CompactionSummary(listOf(Part.Text("summary of earlier steps"))),
            CompactionPrune(listOf(Part.Text("[screenshot removed]"))),
            CompactionEnded(CompactionOutcome.COMPLETED, 40_000),
            TurnEnded(1, TurnEndReason.COMPLETED),
            TurnStarted(2),
            TurnEnded(2, TurnEndReason.FAILED, detail = "flush failed"),
            TaskStopped(StopCause.ERROR),
            SessionResumed(droppedTailBytes = 17),
            TaskStopped(StopCause.USER),
        )

    val events: List<SessionEvent> =
        head + ReasoningDropped(upToSeq = head.indexOfFirst { it === replyWithCall } + 1L) + tail

    private fun seqOf(event: SessionEvent): Long = events.indexOfFirst { it === event } + 1L

    private fun seqsOf(predicate: (SessionEvent) -> Boolean): List<Long> =
        events.withIndex().filter { predicate(it.value) }.map { it.index + 1L }

    /** [events] as consecutive records, with the links and replace ranges the format requires. */
    val records: List<SessionRecord> =
        run {
            val users = seqsOf { it is UserMessage }
            val sideRequests = seqsOf { it is SideRequest }
            val sideResponses = seqsOf { it is SideResponse }
            val dispatch = seqsOf { it is ToolCallRecorded }.single()
            val reply = seqOf(replyWithCall)
            val step = seqsOf { it is StepStarted }.single()
            events.mapIndexed { index, event ->
                val seq = index + 1L
                val surfaceOp =
                    when {
                        event is CompactionSummary -> SurfaceOp.Replace(users.first(), users.last())
                        event is CompactionPrune -> SurfaceOp.Replace(users[1], users[1])
                        else -> SurfaceOp.Append
                    }
                val sources =
                    when {
                        event is ToolCallRecorded -> listOf(reply)
                        event is ToolResultRecorded -> listOf(if (event.callId == call) dispatch else reply)
                        event is SideRequest -> listOf(step)
                        event is SideResponse -> listOf(sideRequests[sideResponses.indexOf(seq)])
                        else -> emptyList()
                    }
                SessionRecord(seq, seq * 10, event, surfaceOp, sources)
            }
        }
}
