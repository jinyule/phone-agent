package dev.phoneagent.core.model

import dev.phoneagent.core.ids.BlobRef

/**
 * Turns a [Credential] into the secret for a request. Injected into adapters when they are
 * built; implemented by the key store and the ChatGPT token source and wired by the app.
 * Secrets never appear in logs, exception messages or `toString`.
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
