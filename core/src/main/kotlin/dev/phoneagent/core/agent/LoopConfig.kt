package dev.phoneagent.core.agent

/**
 * Tunable limits of one task's loop (phase1 §3.7). Validated on construction, so a bad value fails
 * where the configuration is loaded. The security level and attendance are task settings in the
 * log (`task/start`, `task/level-changed`), not configuration.
 *
 * @property maxStepsPerTurn steps one turn may take; the next step ends the turn with `LIMIT`, >= 1.
 * @property maxContinuationsPerTurn times `turnStopping` may keep one turn going, >= 0.
 * @property maxReconfirmations times one call's confirmation is shown again when the re-check after
 *   approval asks for a new one (the screen changed, e.g. a countdown); then the call is refused, >= 0.
 * @property confirmTimeoutMs how long a confirmation card waits before it counts as rejected, > 0.
 * @property questionTimeoutMs how long an `ask_user` question waits, > 0.
 * @property handoffTimeoutMs how long a handed-off step waits for the user, > 0.
 * @property recheckScreenTimeoutMs how long the runtime waits for a fresh screen of its own (the
 *   re-check after approval, the look after a handoff); a slower read counts as no screen, > 0.
 * @property takeBackTimeoutMs how long the runtime waits to take the task back from the user after
 *   a call cut its own handoff off; slower counts as failed (the call then ends with an error), > 0.
 * @property texts model-visible feedback the loop writes into tool results.
 */
public data class LoopConfig(
    val maxStepsPerTurn: Int = DEFAULT_MAX_STEPS,
    val maxContinuationsPerTurn: Int = DEFAULT_MAX_CONTINUATIONS,
    val maxReconfirmations: Int = DEFAULT_MAX_RECONFIRMATIONS,
    val confirmTimeoutMs: Long = DEFAULT_CONFIRM_TIMEOUT_MS,
    val questionTimeoutMs: Long = DEFAULT_QUESTION_TIMEOUT_MS,
    val handoffTimeoutMs: Long = DEFAULT_HANDOFF_TIMEOUT_MS,
    val recheckScreenTimeoutMs: Long = DEFAULT_RECHECK_SCREEN_TIMEOUT_MS,
    val takeBackTimeoutMs: Long = DEFAULT_TAKE_BACK_TIMEOUT_MS,
    val texts: LoopTexts = LoopTexts(),
) {
    init {
        require(maxStepsPerTurn >= 1) { "maxStepsPerTurn must be >= 1, got $maxStepsPerTurn" }
        require(maxContinuationsPerTurn >= 0) { "maxContinuationsPerTurn must be >= 0, got $maxContinuationsPerTurn" }
        require(maxReconfirmations >= 0) { "maxReconfirmations must be >= 0, got $maxReconfirmations" }
        require(confirmTimeoutMs > 0) { "confirmTimeoutMs must be > 0, got $confirmTimeoutMs" }
        require(questionTimeoutMs > 0) { "questionTimeoutMs must be > 0, got $questionTimeoutMs" }
        require(handoffTimeoutMs > 0) { "handoffTimeoutMs must be > 0, got $handoffTimeoutMs" }
        require(recheckScreenTimeoutMs > 0) { "recheckScreenTimeoutMs must be > 0, got $recheckScreenTimeoutMs" }
        require(takeBackTimeoutMs > 0) { "takeBackTimeoutMs must be > 0, got $takeBackTimeoutMs" }
    }

    /** Defaults (phase1 §3.7, §7.2). */
    public companion object {
        /** Steps per turn. */
        public const val DEFAULT_MAX_STEPS: Int = 80

        /** `turnStopping` continuations per turn. */
        public const val DEFAULT_MAX_CONTINUATIONS: Int = 3

        /** A call's confirmation is shown again at most once (contracts addendum §14). */
        public const val DEFAULT_MAX_RECONFIRMATIONS: Int = 1

        /** A confirmation nobody answers is rejected after 15 minutes. */
        public const val DEFAULT_CONFIRM_TIMEOUT_MS: Long = 15 * 60_000L

        /** A question nobody answers expires after 15 minutes. */
        public const val DEFAULT_QUESTION_TIMEOUT_MS: Long = 15 * 60_000L

        /** A handoff nobody finishes expires after 30 minutes (logins take a while). */
        public const val DEFAULT_HANDOFF_TIMEOUT_MS: Long = 30 * 60_000L

        /** A fresh screen the runtime reads itself takes at most 5 seconds. */
        public const val DEFAULT_RECHECK_SCREEN_TIMEOUT_MS: Long = 5_000L

        /** Taking a cut-off handoff's task back takes at most 5 seconds. */
        public const val DEFAULT_TAKE_BACK_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * Feedback the loop itself puts into tool results; model-visible, so changes need the replay
 * fixtures and golden files updated in the same change (AGENTS.md §5). The defaults are Chinese.
 * A text is joined directly to what follows it, without a separator, so each text ends with its own
 * punctuation: texts that end with `：` are followed by a detail.
 */
public data class LoopTexts(
    /** A call after the first one in a reply. */
    val skipped: String =
        "未执行：每条回复只执行第一个工具调用（每步一个动作）。如果仍然需要，请在之后的步骤里再调用。",
    /** A call in a reply that hit the output limit. */
    val maxTokens: String = "未执行：这条回复达到了输出长度上限，其中的工具调用已被丢弃。",
    /** The model named a tool that does not exist or is not available; followed by the name. */
    val unknownTool: String = "未执行：没有这个名字的可用工具：",
    /** The arguments are not JSON; followed by the parser's message. */
    val invalidJson: String = "未执行：参数不是合法的 JSON：",
    /** The arguments do not match the schema; followed by the problems, separated by `；`. */
    val invalidArgs: String = "未执行：参数不符合工具的 schema：",
    /** The `reason` argument is blank. */
    val blankReason: String = "未执行：`reason` 参数必须用用户的语言说明这一步要做什么。",
    /** The user rejected the confirmation card. */
    val rejected: String = "未执行：用户拒绝了这个操作。除非用户要求，不要再重复。",
    /** Nobody answered the confirmation card in time. */
    val confirmExpired: String = "未执行：没有人及时确认这个操作。",
    /** The task is unattended, so nobody can confirm. */
    val confirmUnavailable: String = "未执行：这个操作需要用户确认，但现在没有人能确认。",
    /** The re-check after approval asked for a new confirmation once too often. */
    val changedSinceApproval: String =
        "未执行：用户批准之后屏幕变了，这次批准已经对不上当前的操作。请先观察屏幕，再重试。",
    /** The user did a handed-off step. */
    val handoffDone: String = "用户已经自己完成了这一步。继续之前请先观察屏幕。",
    /** The user gave a handed-off step back undone. */
    val handoffDeclined: String = "未完成：用户没有做这一步，把它交还了回来。",
    /** Nobody took a handed-off step in time. */
    val handoffExpired: String = "未完成：没有人及时接手这一步。",
    /** The task is unattended, so nobody can take over. */
    val handoffUnavailable: String = "未完成：这一步需要用户来做，但现在没有人能接手。",
    /** Moving the task to the user failed. */
    val handoffFailed: String = "未完成：把这一步交给用户时出错了。",
    /**
     * Appended, on a line of its own, to a handed-off call's result when no screen was read after
     * the handoff (it ended unreadable, or was stopped, timed out or broken).
     */
    val screenUnknown: String = "交接之后还没有读到屏幕：请先调用 phone_observe。",
    /** The tool did not finish in time. */
    val timeout: String = "工具没有在限定时间内完成，已被取消。",
    /**
     * Appended to [timeout], [interceptorFault], [stoppedWhileRunning] and [outcomeUnknown] only
     * for calls that may change the phone: those with a `tool/started` on a non-READ verdict.
     */
    val mayHaveTakenEffect: String = "这个操作可能已经生效：请先调用 phone_observe 确认，再做决定。",
    /** The tool threw; followed by the exception's class name only (never its message). */
    val toolError: String = "工具执行出错，异常类型：",
    /** A `toolExecute` extension replaced the judged call or ran it twice. */
    val interceptorFault: String = "未按判定执行：有扩展篡改了这次调用。",
    /** Repair: the task stopped and the call has no `tool/started`, so it never ran. */
    val stoppedBeforeRun: String = "未执行：任务已经停止。",
    /** Repair: the task stopped and the call has a `tool/started` but no result. */
    val stoppedWhileRunning: String = "这次调用运行期间，任务被停止了。",
    /**
     * Repair: the step failed (or the process died) and the call has no `tool/started`, so it
     * never ran; a call still waiting for its approval counts too.
     */
    val notStarted: String = "未执行：这一步在调用开始之前就失败了。",
    /** Repair: the step failed (or the process died) and the call has a `tool/started` but no result. */
    val outcomeUnknown: String = "这一步在调用运行期间失败了，结果未知。",
)
