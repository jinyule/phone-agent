package dev.phoneagent.app.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.core.model.SecretRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** What keeps the debug injection's status truthful: broken settings. */
@RunWith(AndroidJUnit4::class)
class DebugModelConfigGuardTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val provider = FakeProvider()
    private val cipher = SoftwareCipher()
    private val files by lazy { temp.newFolder("files") }
    private val noBackup by lazy { temp.newFolder("no_backup") }
    private val services by lazy { ModelServices(files, noBackup, cipher) }
    private val config by lazy { DebugModelConfig(services) }
    private val settingsFile get() = File(files, ModelSettingsFile.NAME)
    private val keyDir get() = File(noBackup, "credentials")

    @After
    fun stop() {
        provider.close()
    }

    private fun request(
        main: String = "main-model",
        auth: String = "api_key",
        extra: String = "",
        models: String = "",
    ) = """{"baseUrl":"${provider.baseUrl}","auth":"$auth","apiKey":"$KEY","models":{"main":"$main"$models}$extra}"""

    /** Submits [text], waits for its run, and returns the status. */
    private fun submit(text: String): JsonObject {
        runBlocking { config.submit(text.toByteArray())?.join() }
        return statusNow()
    }

    private fun statusNow(): JsonObject {
        val status = config.status()
        assertFalse(status, status.contains(KEY))
        return Json.parseToJsonElement(status).jsonObject
    }

    @Test
    fun brokenSettingsAreLeftAloneUntilAnExplicitReset() {
        services.credentials.put(SecretRef("api-key/custom/aaaaaaaa"), "sk-test-old-not-a-real-key")
        settingsFile.writeText("{ not json")
        val keys = { keyDir.listFiles()!!.associate { it.name to it.readBytes().toList() } }
        val keysBefore = keys()
        val status = submit(request(auth = "none")).fields()
        // Collected first, so a run that saves shows everything it changed.
        assertEquals(
            listOf(mapOf("state" to "settings_broken"), "{ not json", true, 0),
            listOf(status, settingsFile.readText(), keys() == keysBefore, provider.requests.size),
        )

        assertEquals("passed", submit(request(auth = "none", extra = ""","reset":true""")).str("state"))
        assertEquals(emptyList<File>(), keyDir.listFiles()!!.toList())
        assertTrue(settingsFile.readText().contains("main-model"))
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
    }
}

private fun JsonObject.str(name: String) = get(name)?.jsonPrimitive?.content

/** The status's fields as text: strings as they are, anything else as its JSON without quotes. */
private fun JsonObject.fields() =
    mapValues { (_, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: value.toString().replace("\"", "")
    }
