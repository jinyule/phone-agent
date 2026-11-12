package dev.phoneagent.app.model.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Reading `model-settings.json` from disk: only a file that is not there means "no settings yet". */
class ModelSettingsFileTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val stored = Json.encodeToString(serializer<ModelSettings>(), SettingsFixtures.settings())

    /** What loading finds at the settings path after [make] put something there (each in a folder of its own). */
    private fun loadAfter(make: (File) -> Unit): SettingsLoad {
        val file = File(temp.newFolder(), ModelSettingsFile.NAME)
        make(file)
        return ModelSettingsFile(file).load()
    }

    @Test
    fun aFileThatExistsButCannotBeReadIsBrokenNotMissing() {
        val unreadable = SettingsLoad.Broken(SettingsProblem(SettingsProblemKind.UNREADABLE_FILE, null))
        // Collected first, so each shape shows what it loads as.
        val loads =
            mapOf(
                "no file" to loadAfter {},
                "valid file" to loadAfter { it.writeText(stored) },
                "file without read permission" to loadAfter { SettingsFixtures.unreadable(it, stored) },
                "directory in its place" to loadAfter { SettingsFixtures.directoryAt(it) },
            )
        assertEquals(
            mapOf(
                "no file" to SettingsLoad.Empty,
                "valid file" to SettingsLoad.Loaded(SettingsFixtures.settings()),
                "file without read permission" to unreadable,
                "directory in its place" to unreadable,
            ),
            loads,
        )
    }
}
