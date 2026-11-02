/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * core/src/main/kotlin/com/wangzan101/mika/core/shell/HelperHandoff.kt, `HandoffNonces`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: replaced the USB slot by an armed SHA-256 commitment,
 * made matching and consuming separate steps, consuming clears only the matched slot,
 * TTLs are validated constructor parameters, resume nonces are bound to the helper process and
 * overlap by one generation.
 */
package dev.phoneagent.privileged.handoff

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Persisted nonce state of the app. Times are milliseconds since boot (`elapsedRealtime`), which
 * restarts at 0 on every boot, so each time is stored with the boot it belongs to: a launch or armed
 * nonce from another boot, or from an unknown boot, never matches, even when the new uptime falls
 * back inside its TTL.
 *
 * Resume nonces belong to the helper process they were given to ([resumePid],
 * [previousResumePid]) and never expire. [previousResume] is the one a running helper may still
 * hold because the reply that carried [resume] never reached anyone; see [HandoffNonces.admit].
 */
public data class NonceSlots(
    /** Given to the last admitted helper for its next handoff; no expiry, rotated on every admission. */
    val resume: String? = null,
    /** Issued by the app for a helper it launches itself (in-app ADB). */
    val launch: String? = null,
    /** When [launch] was issued. */
    val launchAtMs: Long = 0,
    /** Boot [launch] was issued in; null when unknown. */
    val launchBoot: Int? = null,
    /** SHA-256 (lowercase hex) of a nonce armed from the shell for a helper started from a computer. */
    val armedHash: String? = null,
    /** When [armedHash] was armed. */
    val armedAtMs: Long = 0,
    /** Boot [armedHash] was armed in; null when unknown. */
    val armedBoot: Int? = null,
    /** pid of the helper [resume] was given to. */
    val resumePid: Int? = null,
    /** The resume nonce that was live before [resume] was handed out. */
    val previousResume: String? = null,
    /** pid of the helper [previousResume] was given to. */
    val previousResumePid: Int? = null,
) {
    /** Says which slots are filled, never their values. */
    override fun toString(): String =
        "NonceSlots(resume=${resume != null}/pid $resumePid, previous=${previousResume != null}/pid " +
            "$previousResumePid, launch=${launch != null}@$launchAtMs/boot $launchBoot, " +
            "armed=${armedHash != null}@$armedAtMs/boot $armedBoot)"
}

/** Durable storage for [NonceSlots]; [save] must be durable before it returns. */
public interface NonceStore {
    /** Current slots; empty slots when nothing was saved. */
    public fun load(): NonceSlots

    /** Replaces the stored slots. */
    public fun save(slots: NonceSlots)
}

/** Which slot a presented nonce matched. */
public enum class NonceSlot {
    /** [NonceSlots.resume]. */
    RESUME,

    /** [NonceSlots.previousResume]. */
    PREVIOUS_RESUME,

    /** [NonceSlots.launch]. */
    LAUNCH,

    /** [NonceSlots.armedHash]. */
    ARMED,
}

/**
 * One-time handoff nonces generated or armed by the app. A helper's nonce reaches it only through
 * stdin, because every shell-uid process can read `/proc/<pid>/cmdline` (phase1-architecture §2.2).
 *
 * The app first [match]es (before touching the offered Binder). Once every other check passed and
 * the helper's death is followed, it [admit]s: one save uses the nonce up and hands out the next
 * resume nonce. A null from [admit] means the nonce stopped being live after [match]: the helper is
 * refused. A stale helper's nonce is burnt with [consume]. All methods are serialized on this object.
 *
 * Resume nonces overlap by one generation, so the app can die anywhere in an admission: before the
 * save nothing changed; after it, the reply may never arrive, and the helper still holding the
 * nonce from before can hand off again. The cost: a replaced helper's resume nonce stays valid until
 * the next admission. A replaced helper that ignores its `shutdown()` can come back once and push
 * the new helper out; there is still only one helper connected in the end.
 */
public class HandoffNonces(
    private val store: NonceStore,
    /** Milliseconds since boot. */
    private val clock: () -> Long,
    /** Identifies the current boot (`Settings.Global.BOOT_COUNT`); null when unknown. */
    private val boot: () -> Int?,
    private val launchTtlMs: Long = DEFAULT_LAUNCH_TTL_MS,
    private val armedTtlMs: Long = DEFAULT_ARMED_TTL_MS,
    private val random: () -> String = Nonces::random,
) {
    init {
        require(launchTtlMs > 0) { "launchTtlMs must be > 0, was $launchTtlMs" }
        require(armedTtlMs > 0) { "armedTtlMs must be > 0, was $armedTtlMs" }
    }

    /**
     * Issues the nonce for a helper the app launches itself; replaces any earlier launch nonce.
     * Throws [IllegalStateException] ([BOOT_UNKNOWN]), changing nothing, when the boot is unknown:
     * such a nonce could never match.
     */
    @Synchronized
    public fun issueLaunch(): String {
        val current = checkNotNull(boot()) { BOOT_UNKNOWN }
        return random().also { store.save(store.load().copy(launch = it, launchAtMs = clock(), launchBoot = current)) }
    }

    /**
     * Arms [sha256Hex] (the hash of a nonce chosen by the shell) for one handoff within the armed TTL.
     * Returns null when armed; otherwise why not ([NOT_A_HASH], [BOOT_UNKNOWN]), and nothing changed.
     */
    @Synchronized
    public fun arm(sha256Hex: String?): String? {
        val current = boot()
        val refusal =
            when {
                sha256Hex == null || !Nonces.isWellFormed(sha256Hex) -> NOT_A_HASH
                current == null -> BOOT_UNKNOWN
                else -> null
            }
        if (refusal ==
            null
        ) {
            store.save(store.load().copy(armedHash = sha256Hex, armedAtMs = clock(), armedBoot = current))
        }
        return refusal
    }

    /**
     * The live slot [nonce] matches when presented by the helper process [pid], or null. Resume
     * nonces match only for the process they were given to. Does not change any state.
     */
    @Synchronized
    public fun match(
        nonce: String?,
        pid: Int,
    ): NonceSlot? {
        if (nonce == null || !Nonces.isWellFormed(nonce)) return null
        val slots = store.load()
        return when {
            same(nonce, slots.resume) && slots.resumePid == pid -> {
                NonceSlot.RESUME
            }

            same(nonce, slots.previousResume) && slots.previousResumePid == pid -> {
                NonceSlot.PREVIOUS_RESUME
            }

            same(nonce, slots.launch) && live(slots.launchAtMs, slots.launchBoot, launchTtlMs) -> {
                NonceSlot.LAUNCH
            }

            same(Nonces.sha256Hex(nonce), slots.armedHash) && live(slots.armedAtMs, slots.armedBoot, armedTtlMs) -> {
                NonceSlot.ARMED
            }

            else -> {
                null
            }
        }
    }

    /** Issued at [atMs] of boot [atBoot], which is this boot, and no longer than [ttlMs] ago. */
    private fun live(
        atMs: Long,
        atBoot: Int?,
        ttlMs: Long,
    ): Boolean {
        val current = boot()
        return current != null && atBoot == current && clock() - atMs in 0..ttlMs
    }

    /**
     * Admits the helper process [pid] that presented [nonce], in one save: uses the nonce up and
     * gives [pid] the next resume nonce, which is returned. The resume nonce a running helper may
     * still hold stays valid as [NonceSlots.previousResume]: the one presented, when a resume nonce
     * was presented; otherwise the current one, whose helper is being replaced. Null, and nothing
     * saved, when [nonce] is not live for [pid].
     */
    @Synchronized
    public fun admit(
        nonce: String?,
        pid: Int,
    ): String? {
        val slot = match(nonce, pid) ?: return null
        val slots = store.load()
        val next = random()
        val kept = if (slot == NonceSlot.PREVIOUS_RESUME) previousOf(slots) else currentOf(slots)
        store.save(
            cleared(slots, slot).copy(
                resume = next,
                resumePid = pid,
                previousResume = kept.first,
                previousResumePid = kept.second,
            ),
        )
        return next
    }

    /**
     * Uses up [nonce] of the helper process [pid] without admitting it (a stale helper): clears the
     * slot it matches and returns that slot, or null if it matches none.
     */
    @Synchronized
    public fun consume(
        nonce: String?,
        pid: Int,
    ): NonceSlot? = match(nonce, pid)?.also { store.save(cleared(store.load(), it)) }

    private fun currentOf(slots: NonceSlots): Pair<String?, Int?> = slots.resume to slots.resumePid

    private fun previousOf(slots: NonceSlots): Pair<String?, Int?> = slots.previousResume to slots.previousResumePid

    private fun cleared(
        slots: NonceSlots,
        slot: NonceSlot,
    ): NonceSlots =
        when (slot) {
            NonceSlot.RESUME -> slots.copy(resume = null, resumePid = null)
            NonceSlot.PREVIOUS_RESUME -> slots.copy(previousResume = null, previousResumePid = null)
            NonceSlot.LAUNCH -> slots.copy(launch = null, launchAtMs = 0, launchBoot = null)
            NonceSlot.ARMED -> slots.copy(armedHash = null, armedAtMs = 0, armedBoot = null)
        }

    /**
     * Forgets every nonce: no running helper can hand off again until a new launch.
     *
     * The caller must serialize this with admission (in the app: under `HelperClient`'s lock).
     * Otherwise a clear that lands after an [admit] does not stop that helper.
     */
    @Synchronized
    public fun clear(): Unit = store.save(NonceSlots())

    private fun same(
        a: String,
        b: String?,
    ): Boolean = b != null && MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** Default TTLs. */
    public companion object {
        /** Covers the in-app ADB launch plus one round of delivery retries. */
        public const val DEFAULT_LAUNCH_TTL_MS: Long = 30_000

        /** The computer-side script arms, launches and waits within this window. */
        public const val DEFAULT_ARMED_TTL_MS: Long = 60_000

        /** [arm] refused: the value is not a nonce hash. */
        public const val NOT_A_HASH: String = "not 64 lowercase hex characters (the SHA-256 of a nonce)"

        /** [arm] and [issueLaunch] refused: their nonces are tied to the boot, which is unknown. */
        public const val BOOT_UNKNOWN: String =
            "Settings.Global.BOOT_COUNT is unavailable on this device, so launch and armed nonces " +
                "cannot be tied to this boot; only a running helper can hand off again"
    }
}

/** Nonce format: 32 random bytes as 64 lowercase hex characters; hashes use the same format. */
public object Nonces {
    /** Length of a well-formed nonce or SHA-256 hex string. */
    public const val HEX_LENGTH: Int = 64

    private const val BYTES = 32
    private val hexFormat = HexFormat.Default
    private val rng = SecureRandom()

    /** A fresh nonce from [SecureRandom]. */
    public fun random(): String = ByteArray(BYTES).also(rng::nextBytes).toHexString(hexFormat)

    /** Whether [value] is exactly [HEX_LENGTH] lowercase hex characters. */
    public fun isWellFormed(value: String): Boolean =
        value.length == HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of [value]. */
    public fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHexString(hexFormat)
}
