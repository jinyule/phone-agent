package dev.phoneagent.app.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.core.model.Credential
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The debug injection runs the UI's "test and save" and reports without repeating its input. */
@RunWith(AndroidJUnit4::class)
class DebugModelConfigTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val provider = FakeProvider()
    private val services by lazy {
        ModelServices(
            temp.newFolder("files"),
            temp.newFolder("no_backup"),
            SoftwareCipher(),
        )
    }
    private val config by lazy { DebugModelConfig(services) }

    @After
    fun stop() {
        provider.close()
    }

    private fun request(
        main: String = "main-model",
        auth: String = "api_key",
        extra: String = "",
    ) = """{"baseUrl":"${provider.baseUrl}","auth":"$auth","apiKey":"$KEY","models":{"main":"$main"}$extra}"""

    private fun submit(text: String): JsonObject {
        runBlocking { config.submit(text.toByteArray())?.join() }
        val status = config.status()
        assertFalse(status, status.contains(KEY))
        return Json.parseToJsonElement(status).jsonObject
    }

    @Test
    fun aPassingRequestIsSavedWithItsKeyInTheStore() {
        assertEquals("idle", Json.parseToJsonElement(config.status()).jsonObject.str("state"))
        val status = submit(request())
        assertEquals("passed", status.str("state"))
        assertEquals("PRESENT", status.str("credential"))
        val model = status.array("models").single().jsonObject
        assertEquals("main-model", model.str("model"))
        assertEquals(listOf("PASSED", "PASSED"), model.array("steps").map { it.jsonObject.str("state") })
        val saved = checkNotNull((services.state.value as SettingsState.Ready).settings)
        val ref =
            (
                saved.providers
                    .single()
                    .profile.credential as Credential.ApiKey
            ).secretRef
        assertEquals(KEY, runBlocking { services.credentials.secret(Credential.ApiKey(ref), rejected = false) })
        assertTrue(provider.requests.all { it.authorization == "Bearer $KEY" })
    }

    @Test
    fun aFailureSaysWhichStepWithTheProviderWordsMasked() {
        // The provider's error repeats the Authorization header; core masks the key before the status sees it.
        provider.listingStatus = UNAUTHORIZED
        val model = submit(request()).array("models").single().jsonObject
        assertEquals(listOf("FAILED", "NOT_RUN"), model.array("steps").map { it.jsonObject.str("state") })
        assertEquals("AUTH", model.str("kind"))
        assertTrue(model.str("message").orEmpty().contains("Invalid API key provided"))
        assertNull((services.state.value as SettingsState.Ready).settings)
    }

    @Test
    fun noAuthenticationSendsNoKey() {
        assertEquals("NONE", submit(request(auth = "none")).str("credential"))
        assertTrue(provider.requests.all { it.authorization == null })
    }

    @Test
    fun brokenRequestsSayWhereOnly() {
        for (text in listOf(
            """{"auth":"api_key","apiKey":"$KEY""",
            """{"auth":"maybe","apiKey":"$KEY","models":{"main":"m"}}""",
        )) {
            val status = submit(text)
            assertEquals("broken", status.str("state"))
        }
        assertEquals(
            "broken",
            submit("""{"auth":"api_key","apiKey":"$KEY","models":{"main":"m"},"$KEY":1}""").str("state"),
        )
        assertEquals(emptyList<FakeProvider.Seen>(), provider.requests)
    }

    @Test
    fun aRequestOnItsWayHidesTheLastOutcome() {
        assertEquals("passed", submit(request()).str("state"))
        config.receiving()
        assertEquals("receiving", Json.parseToJsonElement(config.status()).jsonObject.str("state"))
    }

    @Test
    fun unreadableAndOversizedRequestsAreRefusedUnsent() {
        assertNull(config.received(null))
        assertEquals(mapOf("state" to "broken", "reason" to "unreadable"), statusFields())
        val text = request()
        val padded = " ".repeat(DebugModelConfig.MAX_REQUEST_BYTES - text.length) + text
        assertNull(config.received((padded + " ").toByteArray()))
        assertEquals(mapOf("state" to "broken", "reason" to "too_large"), statusFields())
        assertEquals(emptyList<FakeProvider.Seen>(), provider.requests)

        runBlocking { config.received(padded.toByteArray())?.join() }
        assertEquals("passed", Json.parseToJsonElement(config.status()).jsonObject.str("state"))
    }

    private fun statusFields() =
        Json
            .parseToJsonElement(config.status())
            .jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    @Test
    fun invalidFormsListTheirProblems() {
        val status = submit(request(main = "", extra = ""","preset":"no-such-preset""""))
        assertEquals("invalid", status.str("state"))
        assertEquals(
            listOf("UNKNOWN_PRESET", "MAIN_MODEL_MISSING"),
            status.array("problems").map { it.jsonPrimitive.content },
        )
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
        const val UNAUTHORIZED = 401
    }
}

private fun JsonObject.str(name: String) = get(name)?.jsonPrimitive?.content

private fun JsonObject.array(name: String) = getValue(name).jsonArray
