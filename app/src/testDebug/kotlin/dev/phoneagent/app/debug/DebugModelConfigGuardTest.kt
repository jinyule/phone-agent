package dev.phoneagent.app.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.core.model.SecretRef
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import java.io.File

/**
 * What keeps the debug injection's status truthful: inputs the HTTP client refuses, unexpected
 * failures, broken settings, and a second request while one runs.
 */
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
    fun addressesAndHeaderValuesTheHttpClientRefusesAreInvalidNotCrashes() {
        val addresses =
            listOf(0, 65536, 99999).associate {
                "port $it" to
                    submit("""{"baseUrl":"http://127.0.0.1:$it/v1","auth":"none","models":{"main":"m"}}""").fields()
            }
        // A JSON escape: the request carries a non-ASCII header value.
        val header =
            mapOf(
                "header" to submit(request(auth = "none", extra = ""","headers":{"X-Note":"caf\u00e9"}""")).fields(),
            )
        assertEquals(
            addresses.mapValues { mapOf("state" to "invalid", "problems" to "[BASE_URL_INVALID]") } +
                header.mapValues { mapOf("state" to "invalid", "problems" to "[HEADER_INVALID]") },
            addresses + header,
        )
        assertEquals(emptyList<FakeProvider.Seen>(), provider.requests)
    }

    @Test
    fun anUnexpectedFailureIsReportedWithItsClassOnly() {
        cipher.sealFailure = IllegalStateException("sk-test-in-a-message-not-a-real-key")
        val status = submit(request())
        assertEquals(mapOf("state" to "error", "class" to "java.lang.IllegalStateException"), status.fields())
        cipher.sealFailure = null
        assertEquals("passed", submit(request()).str("state"))
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

    @Test
    fun aRequestWhileOneRunsIsRefusedAsBusy() {
        val hold = provider.holdTrials()
        val first = config.submit(request().toByteArray())
        var second: Job? = null
        try {
            assertTrue(hold.arrived())
            second = config.submit(request(main = "model-b").toByteArray())
            assertEquals(mapOf("state" to "busy"), statusNow().fields())
            assertNull(second)
        } finally {
            hold.release()
            runBlocking { listOfNotNull(first, second).joinAll() }
        }
        assertEquals("passed", statusNow().str("state"))
        assertEquals(listOf("main-model"), provider.requests.mapNotNull { it.model })
    }

    @Test
    fun aRequestNonceIsRepeatedInItsStatus() {
        val hold = provider.holdTrials()
        val first = config.submit(request(extra = ""","nonce":"eval-a"""").toByteArray())
        var second: Job? = null
        try {
            assertTrue(config.status(), first != null && hold.arrived())
            assertEquals(
                mapOf("state" to "running", "model" to "main-model", "nonce" to "eval-a"),
                statusNow().fields(),
            )
            second = config.submit(request(main = "model-b", extra = ""","nonce":"eval-b"""").toByteArray())
            assertEquals(mapOf("state" to "busy", "nonce" to "eval-b"), statusNow().fields())
        } finally {
            hold.release()
            runBlocking { listOfNotNull(first, second).joinAll() }
        }
        val done = statusNow()
        assertEquals("passed", done.str("state"))
        assertEquals("eval-a", done.str("nonce"))
        assertEquals(
            "main-model",
            done
                .array("models")
                .single()
                .jsonObject
                .str("model"),
        )
        // Only a token-shaped nonce is accepted, so nothing else of the request can be echoed.
        val longest = "n".repeat(NONCE_MAX)
        assertEquals(longest, submit(request(extra = ""","nonce":"$longest"""")).str("nonce"))
        assertEquals("broken", submit(request(extra = ""","nonce":"has space"""")).str("state"))
        assertEquals("broken", submit(request(extra = ""","nonce":"${longest}n"""")).str("state"))
        assertEquals("broken", submit(request(extra = ""","nonce":""""")).str("state"))
    }

    /**
     * The running request's progress (the next model it checks) does not hide what a newer request
     * reported: its `receiving`, then its `busy`. Each trial waits until let through, so every
     * status is read while the running request waits at a known model.
     */
    @Test
    fun theRunningRequestsProgressHidesNoNewerStatus() {
        val hold = provider.holdTrials()
        val three = ""","light":"small","vision":"seeing""""
        val first =
            config.submit(
                request(models = three, extra = ""","visionImages":true,"nonce":"eval-a"""").toByteArray(),
            )
        var second: Job? = null
        val seen = mutableListOf<Map<String, String>>()
        try {
            assertTrue(config.status(), first != null && hold.arrived())
            config.receiving()
            hold.release(1)
            // The main model passed and the light one waits: its progress came after `receiving`.
            assertTrue(hold.arrived())
            seen += statusNow().fields()
            second = config.submit(request(main = "model-b", extra = ""","nonce":"eval-b"""").toByteArray())
            hold.release(1)
            // The vision model waits: its progress came after `busy`.
            assertTrue(hold.arrived())
            seen += statusNow().fields()
        } finally {
            hold.release()
            runBlocking { listOfNotNull(first, second).joinAll() }
        }
        assertEquals(listOf(mapOf("state" to "receiving"), mapOf("state" to "busy", "nonce" to "eval-b")), seen)
        val done = statusNow()
        assertEquals(mapOf("passed" to "eval-a"), mapOf(done.str("state") to done.str("nonce")))
        assertEquals(listOf("main-model", "small", "seeing"), done.array("models").map { it.jsonObject.str("model") })
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
        const val NONCE_MAX = 64
    }
}

private fun JsonObject.str(name: String) = get(name)?.jsonPrimitive?.content

private fun JsonObject.array(name: String) = getValue(name).jsonArray

/** The status's fields as text: strings as they are, anything else as its JSON without quotes. */
private fun JsonObject.fields() =
    mapValues { (_, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: value.toString().replace("\"", "")
    }
