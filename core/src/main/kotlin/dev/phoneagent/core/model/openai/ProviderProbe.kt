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
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.model.PromptBlock
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.Role
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
import java.util.UUID

/**
 * "Test and save" for OpenAI-style providers (model-layer §4.1): list the models, then let the
 * candidate main model really call a trial tool (a few dozen tokens). The profile passes only when
 * both steps do; the result then carries [VerifiedCapability.TOOL_CALL]. Failures keep the
 * provider's own words for the user. Saving and the "use the preset" hint
 * ([ProviderPresets.matchingOfficial]) are the app's part.
 */
public class ProviderProbe internal constructor(
    private val adapters: OpenAiAdapters,
) {
    /**
     * `GET {baseUrl}/models`. `null` when the provider has no listing endpoint (404/405/501); the
     * tool call step still checks the address. Throws [ModelError] for every other failure, and
     * [IllegalArgumentException] for header values that are not valid HTTP.
     */
    public suspend fun listModels(profile: ProviderProfile): List<ListedModel>? {
        val env = adapters.env
        val url = "${profile.baseUrl}/models".toHttpUrl()
        val headers = profile.httpHeaders()
        val secret = env.secret(profile.credential, rejected = false)
        val exchange = env.exchange(env.get(url, headers, secret)) { it.text(LISTING_LIMIT) }
        val body =
            when (exchange) {
                is Exchange.Done -> {
                    exchange.value
                }

                is Exchange.Failed -> {
                    if (exchange.failure.status in NO_LISTING) return null
                    throw env.error(exchange.failure, plan = false)
                }
            }
        return parseListing(body)
    }

    /**
     * Runs both steps for [candidate]. [capabilities] are the preset's or the listing's initial
     * values; on success they come back with `tools = true` and [VerifiedCapability.TOOL_CALL].
     * [token] is what the model must pass to the trial tool (random by default).
     */
    public suspend fun testAndSave(
        profile: ProviderProfile,
        candidate: ModelId,
        capabilities: ModelCapabilities,
        token: String = "pa-" + UUID.randomUUID().toString().take(TOKEN_LENGTH),
    ): ProbeResult {
        val models =
            try {
                listModels(profile)
            } catch (e: ModelError) {
                return ProbeResult.Failed(ProbeStep.LIST_MODELS, e.kind, e.message.orEmpty())
            }
        return trial(profile, candidate, capabilities, token, models)
    }

    private suspend fun trial(
        profile: ProviderProfile,
        candidate: ModelId,
        capabilities: ModelCapabilities,
        token: String,
        models: List<ListedModel>?,
    ): ProbeResult {
        val model = adapters.model(profile, candidate, capabilities, NO_IMAGES)
        val reply =
            try {
                model.complete(trialRequest(token))
            } catch (e: ModelError) {
                return ProbeResult.Failed(ProbeStep.TOOL_CALL, e.kind, e.message.orEmpty())
            }
        val problem = checkTrialCall(reply, token)
        val verified = capabilities.copy(tools = true, verified = capabilities.verified + VerifiedCapability.TOOL_CALL)
        return problem?.let { ProbeResult.Failed(ProbeStep.TOOL_CALL, null, it) }
            ?: ProbeResult.Passed(models, verified, models?.any { it.id == candidate })
    }

    private fun parseListing(body: String): List<ListedModel> {
        val root =
            try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (e: SerializationException) {
                throw ModelError.BadResponse("model list is not JSON: ${body.take(SNIPPET)}", e)
            }
        val entries =
            root?.array("data") ?: root?.array("models") ?: throw ModelError.BadResponse("model list has no data array")
        return entries
            .mapNotNull { (it as? JsonObject)?.let(::listed) }
            .distinctBy { it.id }
    }

    /** One listing entry; ids that are not usable model ids are skipped. */
    private fun listed(entry: JsonObject): ListedModel? {
        val id = (entry.str("id") ?: entry.str("slug"))?.takeIf { it.isNotBlank() && it.length <= MAX_ID }
        id ?: return null
        val modalities =
            entry
                .obj("architecture")
                ?.array("input_modalities")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val parameters = entry.array("supported_parameters")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return ListedModel(
            id = ModelId(id),
            contextTokens = (entry.int("context_length") ?: entry.int("context_window"))?.takeIf { it > 0 },
            vision = modalities?.let { "image" in it },
            tools = parameters?.let { "tools" in it },
        )
    }

    /** Why [reply] is not the expected trial call, or `null` when it is. */
    private fun checkTrialCall(
        reply: ChatResponse,
        token: String,
    ): String? {
        val call =
            reply.toolCalls.firstOrNull { it.name == TRIAL_TOOL }
                ?: return "the model answered without calling the trial tool: " +
                    reply.parts
                        .filterIsInstance<Part.Text>()
                        .joinToString(" ") { it.text }
                        .take(SNIPPET)
        val args =
            try {
                Json.parseToJsonElement(call.argsJson) as? JsonObject
            } catch (ignoredNotJson: SerializationException) {
                // Reported below with the raw text, which is what the user needs to see.
                null
            }
        val sent = (args?.get("token") as? JsonPrimitive)?.contentOrNull?.trim()
        return if (sent == token) null else "the trial tool got unexpected arguments: ${call.argsJson.take(SNIPPET)}"
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
        const val MAX_ID = 256
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

/** Blobs of the trial request, which has none: any read fails. */
internal val NO_IMAGES: BlobSource = BlobSource { ref -> throw IOException("probe requests carry no images ($ref)") }

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
     */
    public data class Passed(
        val models: List<ListedModel>?,
        val capabilities: ModelCapabilities,
        val candidateListed: Boolean?,
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
