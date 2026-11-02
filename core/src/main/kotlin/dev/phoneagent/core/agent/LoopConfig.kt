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
    }
}

/**
 * Feedback the loop itself puts into tool results; model-visible, so changes need the replay
 * fixtures and golden files updated in the same change (AGENTS.md §5). Texts that end with `:` are
 * followed by a space and a detail.
 */
public data class LoopTexts(
    /** A call after the first one in a reply. */
    val skipped: String =
        "Not run: only the first tool call of a reply is executed (one action per step). " +
            "Call it again in a later step if it is still needed.",
    /** A call in a reply that hit the output limit. */
    val maxTokens: String = "Not run: the reply hit the output limit, so its tool calls were discarded.",
    /** The model named a tool that does not exist or is not available; followed by the name. */
    val unknownTool: String = "Not run: there is no available tool with this name:",
    /** The arguments are not JSON; followed by the parser's message. */
    val invalidJson: String = "Not run: the arguments are not valid JSON:",
    /** The arguments do not match the schema; followed by the problems. */
    val invalidArgs: String = "Not run: the arguments do not match the tool's schema:",
    /** The `reason` argument is blank. */
    val blankReason: String = "Not run: the `reason` argument must say, in the user's language, what this step does.",
    /** The user rejected the confirmation card. */
    val rejected: String = "Not run: the user rejected this action. Do not repeat it unless the user asks.",
    /** Nobody answered the confirmation card in time. */
    val confirmExpired: String = "Not run: nobody confirmed this action in time.",
    /** The task is unattended, so nobody can confirm. */
    val confirmUnavailable: String = "Not run: this action needs the user's confirmation, but nobody can confirm now.",
    /** The re-check after approval asked for a new confirmation once too often. */
    val changedSinceApproval: String =
        "Not run: the screen changed after the user approved, so the approval no longer matches. " +
            "Observe the screen and try again.",
    /** The user did a handed-off step. */
    val handoffDone: String = "The user did this step themselves. Observe the screen before continuing.",
    /** The user gave a handed-off step back undone. */
    val handoffDeclined: String = "Not done: the user gave this step back without doing it.",
    /** Nobody took a handed-off step in time. */
    val handoffExpired: String = "Not done: nobody took over this step in time.",
    /** The task is unattended, so nobody can take over. */
    val handoffUnavailable: String = "Not done: this step needs the user, but nobody can take over now.",
    /** Moving the task to the user failed. */
    val handoffFailed: String = "Not done: handing this step to the user failed.",
    /** Appended to a handed-off call's result when the screen could not be read after the handoff. */
    val screenUnknown: String = "The screen could not be read after the handoff: call phone_observe first.",
    /** The tool did not finish in time. */
    val timeout: String = "The tool did not finish in time and was cancelled.",
    /** Appended to [timeout] and [stoppedWhileRunning] for calls that may change the phone. */
    val mayHaveTakenEffect: String = "It may have taken effect: call phone_observe to check before deciding.",
    /** The tool threw; followed by the error. */
    val toolError: String = "The tool failed:",
    /** A `toolExecute` extension replaced the judged call or ran it twice. */
    val interceptorFault: String = "Not run as judged: an extension tampered with this call.",
    /** The task stopped before the call ran. */
    val stoppedBeforeRun: String = "Not run: the task was stopped.",
    /** The task stopped while the call ran. */
    val stoppedWhileRunning: String = "The task was stopped while this call was running.",
    /** Repair: the step failed before the call was dispatched. */
    val notStarted: String = "Not run: the step failed before this call started.",
    /** Repair: the call was dispatched but no result was recorded. */
    val outcomeUnknown: String =
        "The step failed while this call was running, so its result is unknown. " +
            "It may have taken effect: call phone_observe to check before deciding.",
)
