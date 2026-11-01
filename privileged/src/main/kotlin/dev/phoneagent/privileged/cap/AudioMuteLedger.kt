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

/**
 * Where the ledger survives a helper restart. Implementations report their own failures: the
 * ledger keeps working from memory, and only a helper restart would lose it.
 */
internal interface MuteStore {
    fun read(): String

    fun write(text: String)
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

    /** Records that [packageName] had [mode] before it was muted. */
    fun put(
        packageName: String,
        mode: String,
    ) {
        modes[packageName] = mode
        save()
    }

    fun remove(packageName: String) {
        if (modes.remove(packageName) != null) save()
    }

    /**
     * Restores every recorded package through [write] (true when the op was written) and forgets
     * the ones that came back. Returns whether the ledger is empty afterwards.
     */
    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        packages().forEach { pkg -> if (write(pkg, restoreMode(modes.getValue(pkg)))) remove(pkg) }
        return modes.isEmpty()
    }

    private fun save() {
        store?.write(format(modes))
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.]+) ([a-z_]+)""")
        private val PLAY_AUDIO_MODE = Regex("""PLAY_AUDIO:\s*([a-z_]+)""")

        /** Mode reported when the package has no `PLAY_AUDIO` op set. */
        const val DEFAULT = "default"

        /**
         * What to write back when unmuting. An explicit earlier mode is restored as it was; a
         * package that had none is set to `allow`, because the audio system only plays when
         * `PLAY_AUDIO` is `allow`, and writing an explicit `default` would keep it silent.
         */
        fun restoreMode(prior: String?): String = if (prior.isNullOrEmpty() || prior == DEFAULT) "allow" else prior

        /** `appops get <pkg> PLAY_AUDIO` output to a mode; [DEFAULT] when the op is not set. */
        fun parseMode(output: String): String = PLAY_AUDIO_MODE.find(output)?.groupValues?.get(1) ?: DEFAULT

        fun format(modes: Map<String, String>): String = modes.entries.joinToString("") { "${it.key} ${it.value}\n" }

        fun parse(text: String): Map<String, String> =
            text
                .lineSequence()
                .mapNotNull { LINE.matchEntire(it.trim())?.destructured }
                .associate { (pkg, mode) -> pkg to mode }
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

    override fun write(text: String) {
        try {
            replace(text)
        } catch (e: IOException) {
            Log.w(TAG, "cannot persist the audio-mute ledger to $file", e)
        }
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
