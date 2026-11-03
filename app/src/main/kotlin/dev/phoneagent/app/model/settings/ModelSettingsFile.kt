package dev.phoneagent.app.model.settings

import dev.phoneagent.app.model.writeAtomically
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * [ModelSettings] in [file], replaced atomically ([writeAtomically]). Loading validates everything
 * ([ModelSettings.problem]) and never reads a broken file as empty settings.
 */
internal class ModelSettingsFile(
    private val file: File,
) {
    /** The settings on disk. */
    fun load(): SettingsLoad =
        try {
            parse(file.readText(Charsets.UTF_8))
        } catch (ignoredNoFile: FileNotFoundException) {
            // First run, or after a reset.
            SettingsLoad.Empty
        } catch (ignoredUnreadable: IOException) {
            // Named by kind only; the path is the app's own.
            SettingsLoad.Broken(SettingsProblem(SettingsProblemKind.UNREADABLE_FILE, null))
        }

    /**
     * Replaces the file with [settings]; throws [IOException] when it cannot be written (the old
     * file stays) and [IllegalArgumentException] for settings that would not load.
     */
    fun save(settings: ModelSettings) {
        val problem = settings.problem()
        require(problem == null) { "settings would not load: ${problem?.kind} at ${problem?.where}" }
        writeAtomically(file, JSON.encodeToString(serializer<ModelSettings>(), settings).toByteArray(Charsets.UTF_8))
    }

    /** Deletes the file: the next load is [SettingsLoad.Empty]. */
    fun reset() {
        file.delete()
    }

    companion object {
        /** Its name in `filesDir`. */
        const val NAME: String = "model-settings.json"

        private val JSON = Json { encodeDefaults = true }

        /**
         * [text] as settings. Decoding errors keep only their JSON path and offset: kotlinx quotes the
         * input in its messages, and validation messages are not shown at all.
         */
        fun parse(text: String): SettingsLoad =
            try {
                checked(JSON.decodeFromString(serializer<ModelSettings>(), text))
            } catch (e: SerializationException) {
                // Not JSON, not the settings' shape, or (wrapped, with its path) a value's own validation.
                SettingsLoad.Broken(SettingsProblem(kindOf(e), locate(e.message)))
            } catch (ignoredInvalid: IllegalArgumentException) {
                // A value's own validation (profile, ids, capabilities) refused it while decoding.
                SettingsLoad.Broken(SettingsProblem(SettingsProblemKind.INVALID_VALUE, null))
            }

        private fun checked(settings: ModelSettings): SettingsLoad =
            settings.problem()?.let { SettingsLoad.Broken(it) } ?: SettingsLoad.Loaded(settings)

        /** A value that refused itself (a profile, an id) arrives wrapped, with the path where it was decoded. */
        private fun kindOf(e: SerializationException): SettingsProblemKind {
            val cause = e.cause
            return if (cause is IllegalArgumentException && cause !is SerializationException) {
                SettingsProblemKind.INVALID_VALUE
            } else {
                SettingsProblemKind.MALFORMED
            }
        }

        /** `$.path` and `offset N` from a kotlinx message, without the input it quotes. */
        fun locate(message: String?): String? {
            val text = message.orEmpty()
            val parts =
                listOfNotNull(
                    PATH.find(text)?.groupValues?.get(1),
                    OFFSET
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.let { "offset $it" },
                )
            return parts.joinToString(", ").ifEmpty { null }
        }

        private val PATH = Regex("""at path:? (\$[\w.\[\]]*)""")
        private val OFFSET = Regex("""offset (\d+)""")
    }
}

/** What [ModelSettingsFile.load] found. */
internal sealed interface SettingsLoad {
    /** No settings yet. */
    data object Empty : SettingsLoad

    /** Valid settings. */
    data class Loaded(
        val settings: ModelSettings,
    ) : SettingsLoad

    /** Settings that cannot be used; the UI offers a reset and changes nothing else. */
    data class Broken(
        val problem: SettingsProblem,
    ) : SettingsLoad
}
