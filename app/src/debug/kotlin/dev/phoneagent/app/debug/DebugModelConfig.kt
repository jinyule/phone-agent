package dev.phoneagent.app.debug

import android.content.Context
import android.util.Log
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.SettingsState
import dev.phoneagent.app.model.credential.CredentialStatus
import dev.phoneagent.app.model.settings.ModelSettingsFile
import dev.phoneagent.app.model.setup.AuthChoice
import dev.phoneagent.app.model.setup.ModelCheck
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.app.ui.settings.stepsOf
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.openai.ProbeResult
import dev.phoneagent.core.model.openai.ProviderPresets
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * Debug-only model configuration for emulators and eval runs, without the UI
 * ([DebugModelConfigProvider]). A submitted request goes through the same "test and save" as the
 * form, so every model it names is really called once, and its key ends up in the Keystore-backed
 * store like a typed one. [status] reports how the last request went and never repeats its input,
 * except the request's `nonce`.
 *
 * - One request runs at a time. One that arrives while another runs is not run: the status says
 *   `busy` (with the refused request's nonce) until the running one ends with its own outcome and
 *   its own nonce. The running request's progress (`running` with the model being checked) never
 *   replaces a newer request's `receiving`, `busy` or `broken`; only its outcome does.
 * - While the saved settings are broken a request is not run (`settings_broken`); nothing is
 *   saved or deleted. `"reset": true` first deletes the settings and every key, as the UI's reset.
 * - Anything unexpected ends the request as `error` with the exception's class only.
 *
 * [scope] is never cancelled while the process lives.
 */
internal class DebugModelConfig(
    private val services: ModelServices,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** What [status] says; written only under this object's lock, read without it. */
    @Volatile
    private var last: JsonObject = state("idle")

    /** Whether a request runs; changes together with [last] when it ends (both under this object's lock). */
    private var running = false

    /**
     * A request is on its way: from now until it arrives, [status] says `receiving` rather than how
     * the previous one went.
     */
    @Synchronized
    fun receiving() {
        last = state("receiving")
    }

    /**
     * The request as it arrived, `null` when it could not be read. One larger than
     * [MAX_REQUEST_BYTES] is refused unread; otherwise as [submit].
     */
    fun received(bytes: ByteArray?): Job? =
        when {
            bytes == null -> refused("unreadable")
            bytes.size > MAX_REQUEST_BYTES -> refused("too_large")
            else -> submit(bytes)
        }

    @Synchronized
    private fun refused(reason: String): Job? {
        last = state("broken") { put("reason", reason) }
        return null
    }

    /**
     * Starts "test and save" of the request in [bytes] (UTF-8 JSON, see [DebugModelRequest]), or
     * says `busy` and returns `null` while another request runs.
     */
    @Synchronized
    fun submit(bytes: ByteArray): Job? {
        val request = parsed(bytes) ?: return null
        val nonce = request.nonce
        val starts = !running
        running = true
        val started = state(if (starts) "running" else "busy", nonce)
        last = started
        return if (!starts) {
            null
        } else {
            scope.launch(failures(nonce)) {
                if (request.reset) services.reset() else services.reload()
                var shown = started
                val outcome =
                    services.testAndSave(request.form()) { model ->
                        shown = progress(shown, state("running", nonce) { put("model", model.value) })
                    }
                finish(report(outcome, nonce))
            }
        }
    }

    /**
     * Shows [next] in place of [shown], the running request's last status, and returns what it
     * shows now. Once anything else replaced [shown] (a newer request's `receiving`, `busy` or
     * `broken`), that stays until the running request ends.
     */
    @Synchronized
    private fun progress(
        shown: JsonObject,
        next: JsonObject,
    ): JsonObject {
        if (last !== shown) return shown
        last = next
        return next
    }

    /** Publishes how the running request ended, and lets the next one run. */
    @Synchronized
    private fun finish(status: JsonObject) {
        last = status
        running = false
    }

    private fun failures(nonce: String?) =
        CoroutineExceptionHandler { _, failure ->
            // Only the class: a message can quote the request, key included.
            Log.w(TAG, "request failed (${failure.javaClass.name})")
            finish(state("error", nonce) { put("class", failure.javaClass.name) })
        }

    /** The request in [bytes], or `null` with the status set to where it broke (never what it held). */
    private fun parsed(bytes: ByteArray): DebugModelRequest? =
        try {
            JSON.decodeFromString(serializer<DebugModelRequest>(), bytes.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            // Only where it broke: the input holds the key.
            last = state("broken") { put("where", ModelSettingsFile.locate(e.message)) }
            null
        } catch (ignoredInvalid: IllegalArgumentException) {
            // A value refused itself while decoding; nothing of it is repeated.
            last = state("broken")
            null
        }

    /** The last request's state as JSON. */
    fun status(): String = last.toString()

    private fun report(
        outcome: SetupOutcome,
        nonce: String?,
    ): JsonObject =
        when (outcome) {
            SetupOutcome.SettingsBroken -> {
                state("settings_broken", nonce)
            }

            is SetupOutcome.Invalid -> {
                state("invalid", nonce) {
                    put("problems", buildJsonArray { outcome.problems.forEach { add(JsonPrimitive(it.name)) } })
                }
            }

            is SetupOutcome.Failed -> {
                state("failed", nonce) { put("models", checks(outcome.checks)) }
            }

            is SetupOutcome.NotSaved -> {
                state("not_saved", nonce) {
                    put("models", checks(outcome.checks))
                    put("what", outcome.what.name)
                }
            }

            is SetupOutcome.Saved -> {
                state("passed", nonce) {
                    put("models", checks(outcome.checks))
                    put("credential", credential())
                }
            }
        }

    /** The status of the saved keys: `NONE` without any, else each key's [CredentialStatus]. */
    private fun credential(): String {
        val keys = (services.state.value as? SettingsState.Ready)?.keys.orEmpty()
        return if (keys.isEmpty()) "NONE" else keys.values.joinToString(transform = CredentialStatus::name)
    }

    companion object {
        /** The largest request read. */
        const val MAX_REQUEST_BYTES: Int = 64 * 1024

        private const val TAG = "PhoneAgentDebugModel"

        private val JSON = Json { ignoreUnknownKeys = false }

        @Volatile
        private var instance: Pair<Context, DebugModelConfig>? = null

        /** The process-wide one, on [ModelServices.of]. */
        fun of(context: Context): DebugModelConfig {
            val app = context.applicationContext
            return instance?.takeIf { it.first === app }?.second
                ?: synchronized(this) {
                    instance?.takeIf { it.first === app }?.second
                        ?: DebugModelConfig(ModelServices.of(app)).also {
                            instance = app to it
                        }
                }
        }
    }
}

private fun checks(checks: List<ModelCheck>): JsonArray =
    buildJsonArray {
        for (check in checks) add(checkOf(check))
    }

private fun checkOf(check: ModelCheck): JsonObject =
    buildJsonObject {
        put("model", check.model.value)
        put("roles", buildJsonArray { check.roles.forEach { add(JsonPrimitive(it.name)) } })
        put(
            "steps",
            buildJsonArray {
                for (line in stepsOf(check.result)) {
                    add(
                        buildJsonObject {
                            put("step", line.step.name)
                            put("state", line.state.name)
                        },
                    )
                }
            },
        )
        when (val result = check.result) {
            is ProbeResult.Passed -> {
                result.candidateListed?.let { put("listed", it) }
            }

            is ProbeResult.Failed -> {
                result.kind?.let { put("kind", it.name) }
                result.reason?.let { put("reason", it.name) }
                // Core's text: the provider's words with every key of the run masked.
                put("message", result.message)
            }
        }
    }

/** A status: [name], the request's [nonce] when it has one, and [more]. */
private fun state(
    name: String,
    nonce: String? = null,
    more: JsonObjectBuilder.() -> Unit = {},
): JsonObject =
    buildJsonObject {
        put("state", name)
        nonce?.let { put("nonce", it) }
        more()
    }

/**
 * What `adb shell content write` sends. Not a data class: its `toString` must not show [apiKey].
 *
 * ```json
 * {"preset": "deepseek", "auth": "api_key", "apiKey": "…", "models": {"main": "deepseek-chat"}}
 * {"baseUrl": "http://10.0.2.2:11434/v1", "protocol": "OPENAI_CHAT_COMPLETIONS", "auth": "none",
 *  "models": {"main": "qwen3:8b", "vision": "qwen2.5vl:7b"}, "visionImages": true,
 *  "nonce": "eval-17", "reset": true}
 * ```
 *
 * @property reset delete the saved settings and every key before this request, as the UI's reset
 *   does: the way out of `settings_broken`.
 * @property nonce 1 to 64 of `A-Z a-z 0-9 . _ : -`, repeated in every status of this request so a
 *   reader can tell its own outcome from another request's. It is echoed as is: never put
 *   anything secret in it.
 */
@Serializable
@Suppress("LongParameterList") // One parameter per JSON field of the request.
internal class DebugModelRequest(
    val preset: String? = null,
    val baseUrl: String? = null,
    val protocol: ModelProtocol = ModelProtocol.OPENAI_CHAT_COMPLETIONS,
    val auth: DebugAuth,
    val apiKey: String = "",
    val headers: Map<String, String> = emptyMap(),
    val models: DebugModels,
    val visionImages: Boolean = false,
    val contextTokens: Int? = null,
    val reset: Boolean = false,
    val nonce: String? = null,
) {
    init {
        require(nonce == null || NONCE.matches(nonce)) { "the nonce is 1 to 64 of A-Z a-z 0-9 . _ : -" }
    }

    /** The form this request fills in. */
    fun form(): ProviderForm {
        val start = ProviderForm.start(preset?.let(ProviderPresets::byId)).copy(presetId = preset)
        return start.copy(
            baseUrl = baseUrl ?: start.baseUrl,
            protocol = if (preset == null) protocol else start.protocol,
            auth = if (auth == DebugAuth.NONE) AuthChoice.NONE else AuthChoice.API_KEY,
            apiKey = apiKey,
            headers = headers,
            mainModel = models.main,
            lightModel = models.light,
            visionModel = models.vision,
            visionImages = visionImages,
            contextTokens = contextTokens,
        )
    }
}

private val NONCE = Regex("[A-Za-z0-9._:-]{1,64}")

/** The models of a [DebugModelRequest]; blank light and vision follow the main model. */
@Serializable
internal class DebugModels(
    val main: String,
    val light: String = "",
    val vision: String = "",
)

/** Authentication of a [DebugModelRequest]. */
@Serializable
internal enum class DebugAuth {
    @SerialName("none")
    NONE,

    @SerialName("api_key")
    API_KEY,
}
