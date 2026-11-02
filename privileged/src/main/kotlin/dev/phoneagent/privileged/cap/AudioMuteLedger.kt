/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/AudioMuteLedger.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: the file is optional and written atomically through a
 * caller-supplied store, so the ledger itself is pure logic.
 */
package dev.phoneagent.privileged.cap

import android.util.Log
import java.io.File
import java.io.IOException

/** Where the ledger survives a helper restart. Implementations log their own failures. */
internal interface MuteStore {
    fun read(): String

    /** Replaces the stored ledger with [text]; false when it could not be written. */
    fun write(text: String): Boolean
}

/**
 * Muted package to the `PLAY_AUDIO` app-op mode it had before (research §2.4 step 8). Persisted,
 * because the helper may be killed while apps are muted: the next helper restores the sound.
 * Only packages in the ledger are ever restored, so a mode somebody else set is left alone.
 */
internal class AudioMuteLedger(
    private val store: MuteStore?,
) {
    private val modes = LinkedHashMap<String, String>()

    init {
        val text = store?.let { runCatching { it.read() }.getOrNull() }.orEmpty()
        modes.putAll(parse(text))
    }

    operator fun contains(packageName: String): Boolean = packageName in modes

    operator fun get(packageName: String): String? = modes[packageName]

    fun packages(): List<String> = modes.keys.toList()

    /**
     * Records that [packageName] had [mode] before it was muted. False, and nothing recorded, when
     * the ledger could not be saved: a mute that a restarted helper could not undo must not happen.
     */
    fun put(
        packageName: String,
        mode: String,
    ): Boolean {
        modes[packageName] = mode
        if (save()) return true
        modes.remove(packageName)
        return false
    }

    /** Forgets [packageName]. If the store cannot be written, a later helper restores it once more. */
    fun remove(packageName: String) {
        if (modes.remove(packageName) != null) save()
    }

    /**
     * Restores every recorded package through [write] (true when the op was written) and forgets
     * the ones that came back. Each package is restored on its own (see [releaseEach]): one whose
     * [write] returns false or throws stays in the ledger. Returns whether the ledger is empty
     * afterwards.
     */
    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        releaseEach(packages(), { "the audio of $it" }) { pkg ->
            if (write(pkg, restoreMode(modes.getValue(pkg)))) remove(pkg)
        }
        return modes.isEmpty()
    }

    private fun save(): Boolean = store?.write(format(modes)) ?: true

    companion object {
        private const val TAG = "PhoneAgentHelper"
        private val LINE = Regex("""(\S+) (\S+)""")
        private val PLAY_AUDIO_MODE = Regex("""PLAY_AUDIO:\s*([a-z_]+)""")

        /** Mode reported when the package has no `PLAY_AUDIO` op set. */
        const val DEFAULT = "default"

        /** The app-op modes `appops set` accepts. */
        private val MODES = setOf("allow", "ignore", "deny", DEFAULT, "foreground")

        /** Whether [mode] is an app-op mode `appops set` accepts. */
        fun isMode(mode: String): Boolean = mode in MODES

        /**
         * What to write back when unmuting. An explicit earlier mode is restored as it was; a
         * package that had none is set to `allow`, because the audio system only plays when
         * `PLAY_AUDIO` is `allow`, and writing an explicit `default` would keep it silent.
         */
        fun restoreMode(prior: String?): String = if (prior.isNullOrEmpty() || prior == DEFAULT) "allow" else prior

        /** `appops get <pkg> PLAY_AUDIO` output to a mode; [DEFAULT] when the op is not set. */
        fun parseMode(output: String): String = PLAY_AUDIO_MODE.find(output)?.groupValues?.get(1) ?: DEFAULT

        fun format(modes: Map<String, String>): String = modes.entries.joinToString("") { "${it.key} ${it.value}\n" }

        /**
         * The ledger in [text]. Only lines of a package name ([PackageNames.wellFormed]) and a
         * mode ([isMode]) count; anything else is dropped and logged, never handed to `appops`.
         */
        fun parse(text: String): Map<String, String> =
            text
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line -> entry(line).also { if (it == null) Log.w(TAG, "dropping ledger line '$line'") } }
                .toMap()

        private fun entry(line: String): Pair<String, String>? {
            val (pkg, mode) = LINE.matchEntire(line)?.destructured ?: return null
            return if (PackageNames.wellFormed(pkg) && isMode(mode)) pkg to mode else null
        }
    }
}

/**
 * The ledger in a file the shell user can write; one file per app uid. A failed read or write is
 * logged and otherwise ignored: muting still works, the ledger just would not survive a restart.
 */
internal class MuteFile(
    private val file: File,
) : MuteStore {
    override fun read(): String =
        try {
            if (file.isFile) file.readText() else ""
        } catch (e: IOException) {
            Log.w(TAG, "cannot read $file", e)
            ""
        }

    override fun write(text: String): Boolean {
        try {
            replace(text)
        } catch (e: IOException) {
            Log.w(TAG, "cannot persist the audio-mute ledger to $file", e)
            return false
        }
        return true
    }

    private fun replace(text: String) {
        if (text.isEmpty()) {
            if (file.exists() && !file.delete()) throw IOException("cannot delete $file")
            return
        }
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) throw IOException("cannot rename $tmp to $file")
    }

    private companion object {
        const val TAG = "PhoneAgentHelper"
    }
}
