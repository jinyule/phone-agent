package dev.phoneagent.core.model

import dev.phoneagent.core.ids.BlobRef

/**
 * Turns a [Credential] into the secret for a request. Injected into adapters when they are
 * built; implemented by the key store and the ChatGPT token source and wired by the app.
 * Secrets never appear in logs, exception messages or `toString`.
 *
 * Redaction (adapters, model-layer §2), for the secret a request used:
 * - Content the model produced is never rewritten. When the secret appears in any of it (text,
 *   tool call arguments, reasoning previews, opaque and signed blocks: Anthropic `thinking`,
 *   `redacted_thinking` and `tool_use`, Responses `reasoning` and `encrypted_content`, and the
 *   like), the whole reply fails as [ModelError.BadResponse] with a fixed text and
 *   [ModelError.discardsStreamed] set.
 * - Matching there is exact, on the secret and on its JSON-escaped form, with no fallback by
 *   shape, and every secret is matched whatever its length. A local service that needs no
 *   authentication uses [Credential.None], not a placeholder key.
 * - Diagnostic fields the server wrote (error bodies, refusal categories, metadata) are still
 *   rewritten to `***`, with a fallback by shape.
 */
public fun interface CredentialResolver {
    /**
     * The secret for [credential], or `null` for [Credential.None]. [rejected] is `true` when the
     * previous value was just refused with 401 and a fresh one is needed (refresh the token).
     * Throws [ModelError.Auth] or [ModelError.SignInExpired] when no usable secret can be had.
     */
    public suspend fun secret(
        credential: Credential,
        rejected: Boolean,
    ): String?
}

/**
 * Reads the bytes of a [Part.Image] for an adapter, from the blob store of the session being
 * served. Injected into adapters when they are built.
 */
public fun interface BlobSource {
    /**
     * The bytes of [ref]. Throws `IOException` when the blob is missing or corrupted; the adapter
     * maps that to [ModelError.InvalidRequest].
     */
    public suspend fun bytes(ref: BlobRef): ByteArray
}
