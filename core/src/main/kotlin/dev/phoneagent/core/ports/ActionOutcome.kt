package dev.phoneagent.core.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of `ScreenPort.act` / `launch` (phase1 §3.3). Expected failures are values here, not
 * exceptions; only an unusable surface throws [ScreenUnavailableException].
 *
 * @property status whether the action was performed.
 * @property effect what visibly happened afterwards.
 * @property after the snapshot read back after the screen settled, its `detail` the one the port
 *   read it at; `null` when it could not be read.
 * @property diff [after] compared with the snapshot the action was planned on, which [ScreenDiff.base]
 *   names; `null` when either is missing.
 */
@Serializable
public data class ActionOutcome(
    val status: Status,
    val effect: Effect,
    val after: ScreenSnapshot? = null,
    val diff: ScreenDiff? = null,
) {
    /** Whether the action was carried out. */
    @Serializable
    public sealed interface Status {
        /** Performed. */
        @Serializable
        @SerialName("ok")
        public data object Ok : Status

        /**
         * Not done as requested: it may have partly happened, or whether it happened cannot be
         * told; [reason] says what is known, for the model, in English. It may quote the screen, so
         * the runtime passes it on only when this outcome's [after] was judged shown, and a fixed
         * text otherwise (policy-rules §6.4). A typed reason code could later tell the model more
         * without quoting the screen.
         */
        @Serializable
        @SerialName("failed")
        public data class Failed(
            /** Why, e.g. "element not clickable". */
            val reason: String,
        ) : Status

        /**
         * Not attempted because a device-side guard refused it (e.g. touching this app's own UI).
         * [reason] reaches the model on the same terms as [Failed.reason].
         */
        @Serializable
        @SerialName("refused")
        public data class Refused(
            /** Which guard and why. */
            val reason: String,
        ) : Status

        /**
         * The target ref no longer matches an element, or not reliably: nothing was done on the
         * device, and the model must use a fresh screen. [reason] says why.
         */
        @Serializable
        @SerialName("stale_ref")
        public data class StaleRef(
            /** Why the planned element was not found again. */
            val reason: StaleReason,
        ) : Status
    }

    /**
     * Why a ref planned on an earlier snapshot was not found again ([Status.StaleRef]). [hint] is
     * what the model is told, in English, with the target as "it"; it quotes nothing of the screen.
     */
    @Serializable
    public enum class StaleReason(
        /** The model-facing explanation (tools show it with the stale result). */
        public val hint: String,
    ) {
        /** The snapshot the action was planned on is no longer remembered. */
        FORGOTTEN("the screen it was planned on is too old to check against"),

        /** What tells it apart outside lists changed (a label of its window), or its window is gone. */
        WINDOW_CHANGED("the screen around it changed since it was planned"),

        /** A label of its row changed, or the row is gone. */
        ROW_CHANGED(
            "its row changed since it was planned; if that row's text keeps changing, use its position instead",
        ),

        /** It, or the element now alike, was read only in part or sits in a row cut down to its controls. */
        UNRELIABLE(
            "it cannot be told apart reliably, because the screen was read only in part or its row is cut off; " +
                "scroll it into view or use its position instead",
        ),

        /**
         * It is there, but not where it was when planned; or a press on it would now go elsewhere
         * than the planned snapshot gives it: to another element, to one where that gave none, or
         * to none where that gave one.
         */
        MOVED("it moved since it was planned"),

        /** Several elements are alike and none can be told for it. */
        AMBIGUOUS("several elements now match it"),

        /** Nothing on the screen now is it. */
        NOT_FOUND("it is no longer on the screen"),
    }

    /** Observed effect of the action. */
    @Serializable
    public enum class Effect {
        /** The screen changed. */
        CHANGED,

        /** Nothing visible changed. */
        NO_CHANGE,

        /** A different app came to the front of the surface. */
        NEW_APP,

        /** The app jumped to the main display and was moved back. */
        SPILLED_BACK,

        /** The app showed a rate-limit or "too fast" notice. */
        RATE_LIMITED,

        /** The new page is FLAG_SECURE. */
        SECURE_PAGE,

        /** Not determined (e.g. the action failed or the screen could not be read back). */
        UNKNOWN,
    }
}

/**
 * A captured frame. Not a data class: [bytes] is compared by identity.
 *
 * @property bytes encoded image ([mime]).
 * @property mime e.g. `image/jpeg`.
 * @property width pixel width after scaling, > 0.
 * @property height pixel height after scaling, > 0.
 * @property som whether Set-of-Mark boxes with element numbers are drawn on it.
 */
public class Frame(
    public val bytes: ByteArray,
    public val mime: String,
    public val width: Int,
    public val height: Int,
    public val som: Boolean,
) {
    init {
        require(width > 0 && height > 0) { "Frame size must be positive, got ${width}x$height" }
        require(mime.startsWith("image/")) { "Frame mime must be image/*, got '$mime'" }
    }
}

/**
 * The surface behind a [ScreenPort] cannot be used at all (display released, accessibility
 * service disconnected, helper gone). Per-action failures are [ActionOutcome.Status] instead.
 */
public class ScreenUnavailableException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
