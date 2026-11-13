package dev.phoneagent.core.replay

import dev.phoneagent.core.ports.ScreenUnavailableException
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * `replay.override.json` (optional): what the device did that the session log does not hold, and
 * faults injected on purpose. The runtime's `RecordingScreen` records observations, actions,
 * launches and the frames it kept, and before each capture the judgment that allowed it; it
 * records nothing for an `awaitChange`, an observation or wait that threw or was cut off, or what
 * a capture whose frame it did not keep gave, and for an action that threw it keeps the
 * exception's class only. Record mode writes those facts here; [DeviceScript] merges them with
 * the logged ones.
 *
 * @property format layout version, [FORMAT].
 * @property device the facts, at most one per (call, op).
 */
@Serializable
data class ReplayOverride(
    val format: Int = FORMAT,
    val device: List<DeviceFact> = emptyList(),
) {
    init {
        require(format == FORMAT) { "replay.override.json format $format; this build reads format $FORMAT" }
        val twice =
            device
                .groupingBy { it.call to it.op }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(twice.isEmpty()) { "replay.override.json: more than one fact for (call, op) $twice" }
    }

    /** This override with every call named by [rename] (tokens to concrete ids, or back). */
    fun renamed(rename: (String) -> String): ReplayOverride =
        copy(device = device.map { it.copy(call = it.call?.let(rename)) })

    companion object {
        /** The layout version this build reads and writes. */
        const val FORMAT: Int = 1
    }
}

/** A screen port method, as [DeviceFact] names it. */
@Serializable
enum class DevicePort { SNAPSHOT, ACT, LAUNCH, FRAME, AWAIT_CHANGE }

/**
 * One port call of tool call [call], the [op]-th one it made (from 1, counting logged and unlogged
 * calls alike), or a note on a logged one.
 *
 * A fact with an outcome on a `snapshot` or `awaitChange` is a port call the log does not hold
 * ([unlogged]):
 * - [changed]: what `awaitChange` returned (an `awaitChange` is never logged, so every one needs a fact);
 * - [throws]: `snapshot` or `awaitChange` threw it;
 * - [hangs]: it never returned and was cancelled.
 *
 * A `frame` call is always in the log, as the judgment that allowed the capture ([DeviceScript]);
 * an outcome says what it gave where the log kept no frame: [noFrame] (`null`), [throws], [hangs].
 *
 * Without an outcome it annotates the logged port call at that position: [delayMs] (how long the
 * device took, so polling replays the same number of reads), or for an `act` or `launch` logged as
 * `Failed("threw <class>")`, [throws] gives the exception's message (model-visible in the tool's
 * result) and must name the same class.
 *
 * @property call the tool call (a `{{call:n}}` token in the file), or `null` for a read that belongs
 *   to no call (the read after a resume).
 * @property op position among the call's port calls, from 1.
 * @property port the method.
 * @property delayMs device time before the call returned or threw, >= 0.
 */
@Serializable
data class DeviceFact(
    val call: String?,
    val op: Int,
    val port: DevicePort,
    val changed: Boolean? = null,
    val noFrame: Boolean = false,
    val throws: Thrown? = null,
    val hangs: Boolean = false,
    val delayMs: Long = 0,
) {
    init {
        require(op >= 1) { "replay.override.json: op must be >= 1, got $op" }
        require(delayMs >= 0) { "replay.override.json: delayMs must be >= 0, got $delayMs" }
        val outcomes =
            listOfNotNull(
                "changed".takeIf { changed != null },
                "noFrame".takeIf { noFrame },
                "throws".takeIf { throws != null },
                "hangs".takeIf { hangs },
            )
        require(outcomes.size <= 1) { "replay.override.json ($call, op $op): one outcome at most, got $outcomes" }
        val allowed =
            when (port) {
                DevicePort.AWAIT_CHANGE -> setOf("changed", "throws", "hangs")
                DevicePort.FRAME -> setOf("noFrame", "throws", "hangs")
                DevicePort.SNAPSHOT -> setOf("throws", "hangs")
                DevicePort.ACT, DevicePort.LAUNCH -> setOf("throws")
            }
        require(allowed.containsAll(outcomes)) { "replay.override.json ($call, op $op): $port cannot have $outcomes" }
        require(port != DevicePort.AWAIT_CHANGE || outcomes.isNotEmpty()) {
            "replay.override.json ($call, op $op): an awaitChange needs its outcome"
        }
        require(outcomes.isNotEmpty() || delayMs > 0) { "replay.override.json ($call, op $op): the fact says nothing" }
    }

    /** Whether this is a port call the log does not hold, rather than a note on a logged one. */
    val unlogged: Boolean
        get() =
            when (port) {
                DevicePort.ACT, DevicePort.LAUNCH, DevicePort.FRAME -> false
                DevicePort.SNAPSHOT, DevicePort.AWAIT_CHANGE -> changed != null || throws != null || hangs
            }
}

/**
 * An exception a port call threw.
 *
 * @property type its class name, one of [ThrownTypes.KNOWN].
 * @property message its message.
 */
@Serializable
data class Thrown(
    val type: String,
    val message: String,
) {
    init {
        require(
            type in ThrownTypes.KNOWN,
        ) { "replay.override.json: cannot rebuild a $type; known: ${ThrownTypes.KNOWN}" }
    }
}

/** The exceptions replay can throw for a port call, by class name. */
object ThrownTypes {
    private val builders: Map<String, (String) -> Exception> =
        mapOf(
            ScreenUnavailableException::class.java.name to { ScreenUnavailableException(it) },
            IOException::class.java.name to { IOException(it) },
            IllegalStateException::class.java.name to { IllegalStateException(it) },
            IllegalArgumentException::class.java.name to { IllegalArgumentException(it) },
            RuntimeException::class.java.name to { RuntimeException(it) },
        )

    /** Class names [build] knows. */
    val KNOWN: Set<String> = builders.keys

    /** A fresh exception of [type] with [message]. */
    fun build(
        type: String,
        message: String,
    ): Exception = builders.getValue(type)(message)
}
