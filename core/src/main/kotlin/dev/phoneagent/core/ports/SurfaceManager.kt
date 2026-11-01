package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.TaskId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Owns background displays and moves tasks between them and the main display (phase1 §3.4).
 * All methods are keyed by [TaskId]; there is no "current task".
 */
public interface SurfaceManager {
    /** What the device supports right now; updated by probes and privilege changes. */
    public val capability: StateFlow<SurfaceCapability>

    /**
     * The agent display for [task], created on first use. Unavailability is a value, not an
     * exception, so the caller can fall back to the main display.
     */
    public suspend fun agentDisplay(task: TaskId): SurfaceAllocation

    /** Releases [task]'s display, if any. Idempotent. */
    public suspend fun release(task: TaskId)

    /** Moves [task]'s app to the main display so the user can act; see [takeBack]. */
    public suspend fun handToUser(
        task: TaskId,
        kind: HandoffKind,
    ): HandoffTicket

    /** Moves the app back to the agent display after a handoff. The caller must observe again. */
    public suspend fun takeBack(ticket: HandoffTicket): SurfaceRef
}

/** Result of [SurfaceManager.agentDisplay]. */
public sealed interface SurfaceAllocation {
    /** The task's display. */
    public data class Ready(
        /** The display to drive. */
        val surface: SurfaceRef,
    ) : SurfaceAllocation

    /** No agent display; the task may run on the main display instead. */
    public data class Unavailable(
        /** Why. */
        val reason: SurfaceUnavailableReason,
        /** Diagnostic detail for logs and the health page, in English. */
        val detail: String,
    ) : SurfaceAllocation
}

/** Device support for agent displays. */
@Serializable
public sealed interface SurfaceCapability {
    /** Not probed yet. */
    @Serializable
    @SerialName("unknown")
    public data object Unknown : SurfaceCapability

    /** Agent displays work; [concurrentTasks] when several tasks may each have one (API 34+). */
    @Serializable
    @SerialName("available")
    public data class Available(
        /** Several agent displays may exist at once. */
        val concurrentTasks: Boolean,
    ) : SurfaceCapability

    /** Agent displays do not work. */
    @Serializable
    @SerialName("unavailable")
    public data class Unavailable(
        /** Why. */
        val reason: SurfaceUnavailableReason,
        /** Diagnostic detail, in English. */
        val detail: String,
    ) : SurfaceCapability
}

/** Why there is no agent display. */
@Serializable
public enum class SurfaceUnavailableReason {
    /** Android older than 14 (D2). */
    API_TOO_OLD,

    /** The privileged helper is not running or not handed over. */
    PRIVILEGE_NOT_READY,

    /** The helper runs but the capability probe failed (ROM restriction). */
    PROBE_FAILED,

    /** Display creation failed for another reason. */
    CREATE_FAILED,
}

/** Why a task is handed to the user (`hand_to_user kind`, policy hand-offs). */
@Serializable
public enum class HandoffKind {
    /** A login page. */
    LOGIN,

    /** A password field outside a login page. */
    PASSWORD,

    /** A captcha or human verification. */
    CAPTCHA,

    /** Payment confirmation or payment password. */
    PAYMENT,

    /** A FLAG_SECURE page the agent cannot see. */
    SECURE_PAGE,

    /** The user has to choose (e.g. an account picker). */
    CHOOSE,

    /** Anything else; the reason text explains. */
    OTHER,
}

/**
 * Proof of a handoff in progress; pass it back to [SurfaceManager.takeBack].
 *
 * @property task the task handed over.
 * @property kind why.
 * @property from the agent display the app came from.
 * @property token implementation-private state (e.g. the Android task id), opaque to callers.
 */
@Serializable
public data class HandoffTicket(
    val task: TaskId,
    val kind: HandoffKind,
    val from: SurfaceRef,
    val token: String,
)
