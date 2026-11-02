package dev.phoneagent.core.model.openai

import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.CacheLayout
import dev.phoneagent.core.model.ChatRequest
import dev.phoneagent.core.model.ChatResponse
import dev.phoneagent.core.model.Message
import dev.phoneagent.core.model.ModelCapabilities
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.ModelErrorKind
import dev.phoneagent.core.model.ModelInfo
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.Role
import dev.phoneagent.core.model.StopReason
import dev.phoneagent.core.model.ToolDef
import dev.phoneagent.core.model.VerifiedCapability
import dev.phoneagent.core.model.openai.WireSupport.array
import dev.phoneagent.core.model.openai.WireSupport.int
import dev.phoneagent.core.model.openai.WireSupport.obj
import dev.phoneagent.core.model.openai.WireSupport.str
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * "Test and save" for OpenAI-style providers (model-layer §4.1): list the models, then let the
 * candidate main model really call a trial tool (a few dozen tokens). The profile passes only when
 * both steps do; the result then carries [VerifiedCapability.TOOL_CALL] and the [WireOptions] the
 * trial passed with. This is the only place where request options are negotiated: models never
 * change their options at run time. Failures keep the provider's own words for the user. Saving
 * and the "use the preset" hint ([ProviderPresets.matchingOfficial]) are the app's part.
 */
public class ProviderProbe internal constructor(
    private val adapters: OpenAiAdapters,
) {
    /**
     * `GET {baseUrl}/models`. `null` when the provider has no listing endpoint (404/405/501); the
     * tool call step still checks the address. Throws [ModelError] for every other failure, and
     * [IllegalArgumentException] for header values that are not valid HTTP.
     */
    public suspend fun listModels(profile: ProviderProfile): List<ListedModel>? = listing(profile, adapters.env)?.models

    private suspend fun listing(
        profile: ProviderProfile,
        env: AdapterEnv,
    ): Listing? {
        val url = "${profile.baseUrl}/models".toHttpUrl()
        val headers = profile.httpHeaders()
        val secret = env.secret(profile.credential, rejected = false)
        val redactor = env.redactor(secret)
        val exchange = env.exchange(env.get(url, headers, secret), redactor) { it.text(LISTING_LIMIT) }
        val body =
            when (exchange) {
                is Exchange.Done -> {
                    exchange.value
                }

                is Exchange.Failed -> {
                    if (exchange.failure.status in NO_LISTING) return null
                    throw env.error(exchange.failure, plan = false, redactor)
                }
            }
        return parseListing(body, redactor)
    }

    /**
     * Runs both steps for [candidate]. [capabilities] are the preset's or the listing's initial
     * values; on success they come back with `tools = true` and [VerifiedCapability.TOOL_CALL].
     * [token] is what the model must pass to the trial tool (random by default).
     *
     * [wire] is the request shape to start from. When the provider refuses the trial with HTTP 400
     * or 422 over an optional field the adapters send — `parallel_tool_calls`, `stream_options`,
     * Responses `include` — the trial runs again without it, each field at most once;
     * [ProbeResult.Passed.wire] is the shape that passed, for the app to save with the profile. A
     * field counts as refused when the error's structured `param` names it or, without a `param`,
     * when the provider's message names one of the Chat fields. Nothing else refuses a field: not
     * another status, and not the adapters' own words (a redirect's target names no refusal, so a
     * 3xx fails the trial at once). A field turned off for a trial that then fails anyway is not
     * reported. Throws [IllegalArgumentException], before any request, when [wire] recovers leaked
     * calls in a format the route does not allow ([WireOptions.allowedLeakedCalls]).
     */
    public suspend fun testAndSave(
        profile: ProviderProfile,
        candidate: ModelId,
        capabilities: ModelCapabilities,
        token: String = "pa-" + UUID.randomUUID().toString().take(TOKEN_LENGTH),
        wire: WireOptions = WireOptions.defaultsFor(profile),
    ): ProbeResult {
        wire.requireFits(profile)
        // Records every secret this run's requests use: what the results must not show.
        val env = adapters.env.recording()
        val listing =
            try {
                listing(profile, env)
            } catch (e: ModelError) {
                return ProbeResult.Failed(
                    ProbeStep.LIST_MODELS,
                    e.kind,
                    Redactor(env.known()).text(e.message.orEmpty()),
                )
            }
        return Trial(profile, candidate, capabilities, token, env).run(listing, wire)
    }

    /**
     * The trial call of one "test and save", with the negotiation of optional fields. Its results
     * mask every secret the run sent ([AdapterEnv.known]).
     */
    private inner class Trial(
        val profile: ProviderProfile,
        val candidate: ModelId,
        val capabilities: ModelCapabilities,
        val token: String,
        val env: AdapterEnv,
    ) {
        suspend fun run(
            listing: Listing?,
            wire: WireOptions,
        ): ProbeResult {
            var options = wire
            while (true) {
                val reply =
                    try {
                        adapters
                            .model(
                                profile,
                                ModelInfo(profile.id, candidate, profile.protocol, capabilities),
                                NO_IMAGES,
                                options,
                                env,
                            ).complete(trialRequest(token))
                    } catch (e: ModelError) {
                        options =
                            WireNegotiation.without(options, profile.protocol, e, env.statusOf(e)) ?: return failed(e)
                        null
                    }
                if (reply != null) return result(reply, listing, options)
            }
        }

        private fun failed(error: ModelError): ProbeResult =
            ProbeResult.Failed(ProbeStep.TOOL_CALL, error.kind, Redactor(env.known()).text(error.message.orEmpty()))

        /**
         * The outcome of a trial that got [reply]. Every secret the run sent masks it, including
         * ones handed out after the listing: an entry holding one is left out of the models.
         */
        private fun result(
            reply: ChatResponse,
            listing: Listing?,
            wire: WireOptions,
        ): ProbeResult {
            val redactor = Redactor(env.known())
            val problem = checkTrialCall(reply, token, redactor)
            val verified =
                capabilities.copy(tools = true, verified = capabilities.verified + VerifiedCapability.TOOL_CALL)
            val models = listing?.models?.filter { redactor.verbatim(it.id.value) != null }
            return problem?.let { ProbeResult.Failed(ProbeStep.TOOL_CALL, null, it) }
                ?: ProbeResult.Passed(models, verified, listing?.let { candidate in it.ids }, wire)
        }
    }

    private fun parseListing(
        body: String,
        redactor: Redactor,
    ): Listing {
        val root =
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (ignoredQuotingParseError: SerializationException) {
                // Its message quotes the input cut at a fixed width, which can split a secret before it is masked.
                throw ModelError.BadResponse("model list is not JSON: ${redactor.text(body).take(SNIPPET)}")
            }
        val entries =
            root?.array("data") ?: root?.array("models") ?: throw ModelError.BadResponse("model list has no data array")
        val usable = entries.mapNotNull { entry -> (entry as? JsonObject)?.let(::listed) }
        return Listing(
            models = usable.filter { redactor.verbatim(it.id.value) != null }.distinctBy { it.id },
            ids = usable.mapTo(HashSet()) { it.id },
        )
    }

    /** One listing entry, its id as listed; an entry whose id is not a usable model id is skipped. */
    private fun listed(entry: JsonObject): ListedModel? {
        val id = (entry.str("id") ?: entry.str("slug"))?.let(::modelIdOf)
        id ?: return null
        val modalities =
            entry
                .obj("architecture")
                ?.array("input_modalities")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val parameters = entry.array("supported_parameters")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return ListedModel(
            id = id,
            contextTokens = (entry.int("context_length") ?: entry.int("context_window"))?.takeIf { it > 0 },
            vision = modalities?.let { "image" in it },
            tools = parameters?.let { "tools" in it },
        )
    }

    /** [id] as a model id, or `null` when the provider listed a name no model id can hold. */
    private fun modelIdOf(id: String): ModelId? =
        try {
            ModelId(id)
        } catch (ignoredIllegalRemoteId: IllegalArgumentException) {
            // An illegal remote id (blank, too long, control characters): only its own entry is skipped.
            null
        }

    /**
     * Why [reply] is not the expected trial call, or `null` when it is. Only a reply that finished
     * as a tool step ([StopReason.TOOL_USE]) verifies anything: one cut at the output limit or
     * left incomplete fails even with the right call. A call recovered from the answer text does
     * not count either: the provider's tool channel is what is being verified. What it quotes
     * from the reply is masked with [redactor], then cut; arguments are masked as JSON
     * ([Redactor.json]): written back in the standard escaping when they parse, and not quoted at
     * all when they do not and their lenient decoding holds a credential.
     */
    private fun checkTrialCall(
        reply: ChatResponse,
        token: String,
        redactor: Redactor,
    ): String? {
        val call = reply.toolCalls.firstOrNull { it.name == TRIAL_TOOL }
        val stop = reply.stopReason
        return when {
            stop != StopReason.TOOL_USE && (call != null || stop != StopReason.END_TURN) -> {
                "the reply did not finish normally (stop reason $stop), so the trial call is not verified"
            }

            call == null -> {
                "the model answered without calling the trial tool: " +
                    redactor.text(reply.parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }).take(SNIPPET)
            }

            reply.providerMetadata?.array(RECOVERED_TOOL_CALLS)?.contains(JsonPrimitive(call.id.value)) == true -> {
                "the model wrote the trial call into its answer text instead of calling the tool"
            }

            tokenOf(call.argsJson) == token -> {
                null
            }

            else -> {
                "the trial tool got unexpected arguments: ${redactor.json(call.argsJson).take(SNIPPET)}"
            }
        }
    }

    /** The trial call's `token` argument, or `null` when the arguments are not an object holding one. */
    private fun tokenOf(argsJson: String): String? {
        val args =
            try {
                Json.parseToJsonElement(argsJson) as? JsonObject
            } catch (ignoredNotJson: SerializationException) {
                // Reported by the caller, which quotes the text through Redactor.json.
                null
            }
        return (args?.get("token") as? JsonPrimitive)?.contentOrNull?.trim()
    }

    private fun trialRequest(token: String): ChatRequest =
        ChatRequest(
            system = listOf(PromptBlock("probe", "You are a connection check. Do what the user asks, using the tool.")),
            tools = listOf(ToolDef(TRIAL_TOOL, "Confirms that the connection works.", TRIAL_SCHEMA)),
            messages = listOf(Message(Role.USER, listOf(Part.Text("Call $TRIAL_TOOL with token \"$token\".")))),
            cache = CacheLayout.NONE,
        )

    private companion object {
        const val TRIAL_TOOL = "confirm_connection"
        const val TOKEN_LENGTH = 8
        const val SNIPPET = 300
        const val LISTING_LIMIT = 8L * 1024 * 1024
    }
}

private val NO_LISTING = setOf(404, 405, 501)

private val TRIAL_SCHEMA: JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("token") {
                put("type", "string")
                put("description", "The token the user gave.")
            }
        }
        putJsonArray("required") { add("token") }
        put("additionalProperties", false)
    }

/** Which optional request field a refused trial names (see [ProviderProbe.testAndSave]). */
internal object WireNegotiation {
    /**
     * [options] without the optional field [error] refuses, or `null` when it names none that is
     * still sent. Only the provider's own refusal counts: an [ModelError.InvalidRequest] made from
     * an HTTP [status] in [REFUSALS].
     */
    fun without(
        options: WireOptions,
        protocol: ModelProtocol,
        error: ModelError,
        status: Int?,
    ): WireOptions? {
        val refused = (error as? ModelError.InvalidRequest)?.takeIf { status in REFUSALS } ?: return null
        val param = refused.param?.lowercase(Locale.ROOT)
        val message = refused.detail.lowercase(Locale.ROOT)
        return when (protocol) {
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> chat(options, param, message)
            ModelProtocol.OPENAI_RESPONSES -> responses(options, param)
            ModelProtocol.ANTHROPIC_MESSAGES -> null
        }
    }

    private fun chat(
        options: WireOptions,
        param: String?,
        message: String,
    ): WireOptions? {
        // A structured param decides; without one, the message must name the (distinctive) field.
        fun names(field: String) = if (param != null) names(param, field) else field in message
        val chat = options.chat
        return when {
            chat.parallelToolCalls &&
                names(
                    PARALLEL_TOOL_CALLS,
                )
            -> options.copy(chat = chat.copy(parallelToolCalls = false))

            chat.streamUsage && names(STREAM_OPTIONS) -> options.copy(chat = chat.copy(streamUsage = false))

            else -> null
        }
    }

    /** `include` is too common a word to look for in a message: only the structured param counts. */
    private fun responses(
        options: WireOptions,
        param: String?,
    ): WireOptions? =
        options
            .takeIf { it.responses.includeEncryptedReasoning && param != null && names(param, INCLUDE) }
            ?.let { it.copy(responses = it.responses.copy(includeEncryptedReasoning = false)) }

    /** Whether [param] is [field] or a part of it (`stream_options.include_usage`, `include[0]`). */
    private fun names(
        param: String,
        field: String,
    ): Boolean = param == field || param.startsWith("$field.") || param.startsWith("$field[")

    /** HTTP statuses of a provider refusing a request field: 400, and 422 (FastAPI-style validation). */
    private val REFUSALS = setOf(Status.BAD_REQUEST, Status.UNPROCESSABLE)
    private const val PARALLEL_TOOL_CALLS = "parallel_tool_calls"
    private const val STREAM_OPTIONS = "stream_options"
    private const val INCLUDE = "include"
}

/** Blobs of the trial request, which has none: any read fails. */
internal val NO_IMAGES: BlobSource = BlobSource { ref -> throw IOException("probe requests carry no images ($ref)") }

/**
 * A parsed listing. [models] is what may be shown: an entry whose id holds a secret the request
 * sent ([Redactor.verbatim]) is left out rather than shown rewritten ("test and save" filters it
 * again with every secret its run sent). [ids] is every usable id as listed, for deciding whether
 * the candidate is listed; it never goes into a result.
 */
private class Listing(
    val models: List<ListedModel>,
    val ids: Set<ModelId>,
)

/**
 * A model from a provider's listing. Capability fields are `null` when the listing does not say.
 *
 * @property id the wire model name.
 * @property contextTokens context window, or `null`.
 * @property vision accepts images, or `null`.
 * @property tools supports tool calling, or `null`.
 */
public data class ListedModel(
    val id: ModelId,
    val contextTokens: Int? = null,
    val vision: Boolean? = null,
    val tools: Boolean? = null,
)

/** Which "test and save" step failed. */
public enum class ProbeStep {
    /** `GET /models`. */
    LIST_MODELS,

    /** The trial tool call. */
    TOOL_CALL,
}

/** Outcome of [ProviderProbe.testAndSave]. */
public sealed interface ProbeResult {
    /**
     * Both steps passed.
     *
     * @property models the listing, or `null` when the provider has none.
     * @property capabilities the input capabilities with the verified tool call.
     * @property candidateListed whether the listing contains the candidate; `null` without a listing.
     * @property wire the request shape the trial passed with; save it with the profile.
     */
    public data class Passed(
        val models: List<ListedModel>?,
        val capabilities: ModelCapabilities,
        val candidateListed: Boolean?,
        val wire: WireOptions,
    ) : ProbeResult

    /**
     * A step failed.
     *
     * @property step which one.
     * @property kind the classified error, or `null` when the model answered but not with the trial call.
     * @property message what went wrong, with the provider's own words when it sent any.
     */
    public data class Failed(
        val step: ProbeStep,
        val kind: ModelErrorKind?,
        val message: String,
    ) : ProbeResult
}
