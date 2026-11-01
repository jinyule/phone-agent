/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * core/src/main/kotlin/com/wangzan101/mika/core/shell/HelperHandoff.kt, `HandoffNonces`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: replaced the USB slot by an armed SHA-256 commitment,
 * made matching and consuming separate steps, consuming clears only the matched slot,
 * TTLs are validated constructor parameters.
 */
package dev.phoneagent.privileged.handoff

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Persisted nonce state of the app. Times are milliseconds since boot (`elapsedRealtime`), which
 * restarts at 0 on every boot, so each time is stored with the boot it belongs to: a launch or armed
 * nonce from another boot, or from an unknown boot, never matches, even when the new uptime falls
 * back inside its TTL.
 */
public data class NonceSlots(
    /** Given to the accepted helper for its next handoff; no expiry, rotated on every acceptance. */
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
) {
    /** Says which slots are filled, never their values. */
    override fun toString(): String =
        "NonceSlots(resume=${resume != null}, launch=${launch != null}@$launchAtMs/boot $launchBoot, " +
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

    /** [NonceSlots.launch]. */
    LAUNCH,

    /** [NonceSlots.armedHash]. */
    ARMED,
}

/**
 * One-time handoff nonces generated or armed by the app. A helper's nonce reaches it only through
 * stdin, because every shell-uid process can read `/proc/<pid>/cmdline` (phase1-architecture §2.2).
 *
 * The app first [match]es (before touching the offered Binder), then [consume]s once every other
 * check passed and the helper's death is followed, and gives the accepted helper a fresh
 * [issueResume] nonce. A null from [consume] means the nonce stopped being live after [match]: the
 * helper is refused. All methods are serialized on this object.
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

    /** Issues the nonce for a helper the app launches itself; replaces any earlier launch nonce. */
    @Synchronized
    public fun issueLaunch(): String =
        random().also { store.save(store.load().copy(launch = it, launchAtMs = clock(), launchBoot = boot())) }

    /**
     * Arms [sha256Hex] (the hash of a nonce chosen by the shell) for one handoff within the armed TTL.
     * Returns false, and changes nothing, when it is not 64 lowercase hex characters.
     */
    @Synchronized
    public fun arm(sha256Hex: String?): Boolean {
        if (sha256Hex == null || !Nonces.isWellFormed(sha256Hex)) return false
        store.save(store.load().copy(armedHash = sha256Hex, armedAtMs = clock(), armedBoot = boot()))
        return true
    }

    /** The live slot [nonce] matches, or null. Does not change any state. */
    @Synchronized
    public fun match(nonce: String?): NonceSlot? {
        if (nonce == null || !Nonces.isWellFormed(nonce)) return null
        val slots = store.load()
        return when {
            same(nonce, slots.resume) -> {
                NonceSlot.RESUME
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

    /** Uses up [nonce]: clears the slot it matches and returns that slot, or null if it matches none. */
    @Synchronized
    public fun consume(nonce: String?): NonceSlot? {
        val slot = match(nonce) ?: return null
        val slots = store.load()
        store.save(
            when (slot) {
                NonceSlot.RESUME -> slots.copy(resume = null)
                NonceSlot.LAUNCH -> slots.copy(launch = null, launchAtMs = 0, launchBoot = null)
                NonceSlot.ARMED -> slots.copy(armedHash = null, armedAtMs = 0, armedBoot = null)
            },
        )
        return slot
    }

    /** Issues the next resume nonce, replacing the previous one (whose helper is being replaced). */
    @Synchronized
    public fun issueResume(): String = random().also { store.save(store.load().copy(resume = it)) }

    /**
     * Forgets every nonce: no running helper can hand off again until a new launch.
     *
     * The caller must serialize this with admission (in the app: under `HelperClient`'s lock).
     * Otherwise a clear that lands after an admission's [consume] does not stop that helper.
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
