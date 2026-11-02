package dev.phoneagent.core.ports

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of `ScreenPort.act` / `launch` (phase1 §3.3). Expected failures are values here, not
 * exceptions; only an unusable surface throws [ScreenUnavailableException].
 *
 * @property status whether the action was performed.
 * @property effect what visibly happened afterwards.
 * @property after the snapshot read back after the screen settled; `null` when it could not be read.
 * @property diff [after] compared with the snapshot the action was planned on; `null` when either is missing.
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
         * told; [reason] says what is known, for the model, in English.
         */
        @Serializable
        @SerialName("failed")
        public data class Failed(
            /** Why, e.g. "element not clickable". */
            val reason: String,
        ) : Status

        /** Not attempted because a device-side guard refused it (e.g. touching this app's own UI). */
        @Serializable
        @SerialName("refused")
        public data class Refused(
            /** Which guard and why. */
            val reason: String,
        ) : Status

        /** The target ref no longer matches any element; the model must observe again. */
        @Serializable
        @SerialName("stale_ref")
        public data object StaleRef : Status
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
