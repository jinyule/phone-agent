package dev.phoneagent.app.model.credential

import dev.phoneagent.app.model.TEMP_SUFFIX
import dev.phoneagent.app.model.writeAtomically
import dev.phoneagent.core.model.Credential
import dev.phoneagent.core.model.CredentialResolver
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.SecretRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * The API keys of the configured providers (model-layer §2, §4.1), one file per [SecretRef] in
 * [dir], sealed by [cipher]. The app keeps [dir] in `noBackupFilesDir`, while the non-secret
 * settings live in `filesDir`: the manifest turns backup and device transfer off, but if settings
 * ever arrive without their keys (a restore, a cleared Keystore), every key reads as
 * [CredentialStatus.UNREADABLE] or [CredentialStatus.MISSING] and the UI asks for it again. A key
 * never becomes an empty secret.
 *
 * File format: one version byte (1), the 12-byte IV, then the ciphertext with its 16-byte tag.
 * The AAD is `phoneagent-credential-v1:` plus the ref, so a file copied under another ref does not
 * open. Files are named by the SHA-256 of the ref and replaced atomically ([writeAtomically]).
 *
 * Keys never reach logs, exception messages or `toString`: failures name the ref only.
 */
internal class CredentialStore(
    private val dir: File,
    private val cipher: SecretCipher,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CredentialResolver {
    /**
     * Stores [key] under [ref], replacing what was there. Throws when it cannot be sealed (Keystore)
     * or written ([IOException]); the previous file then stays.
     */
    fun put(
        ref: SecretRef,
        key: String,
    ) {
        val plain = key.toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(plain, aad(ref))
        plain.fill(0)
        writeAtomically(fileOf(ref), CredentialFormat.encode(sealed))
    }

    /** Deletes the key under [ref]; nothing happens when there is none. */
    fun remove(ref: SecretRef) {
        fileOf(ref).delete()
    }

    /** Whether the key under [ref] can be read; decrypts it to find out and forgets it. */
    fun status(ref: SecretRef): CredentialStatus =
        when (val read = read(ref)) {
            is Read.Key -> {
                read.plain.fill(0)
                CredentialStatus.PRESENT
            }

            Read.Missing -> {
                CredentialStatus.MISSING
            }

            Read.Unreadable -> {
                CredentialStatus.UNREADABLE
            }
        }

    /**
     * Deletes every stored key whose ref is not in [keep]: what a save interrupted between writing
     * a new key and the settings, or between the settings and deleting the old key, leaves behind.
     * Only called once settings have loaded successfully.
     */
    fun retainOnly(keep: Set<SecretRef>) {
        val kept = keep.mapTo(HashSet()) { fileOf(it).name }
        dir
            .listFiles()
            .orEmpty()
            .filter { STORED_NAME.matches(it.name.removeSuffix(TEMP_SUFFIX)) && it.name !in kept }
            .forEach { it.delete() }
    }

    /**
     * The key of [credential]: `null` for [Credential.None]. A missing or unreadable key, and a
     * ChatGPT sign-in (not available yet), throw [ModelError.Auth] naming the ref only. An API key
     * has nothing to refresh, so [rejected] changes nothing.
     */
    override suspend fun secret(
        credential: Credential,
        rejected: Boolean,
    ): String? =
        when (credential) {
            Credential.None -> {
                null
            }

            is Credential.ChatGptPlan -> {
                throw ModelError.Auth("ChatGPT sign-in is not supported yet (${credential.sessionRef})")
            }

            is Credential.ApiKey -> {
                key(credential.secretRef)
            }
        }

    private suspend fun key(ref: SecretRef): String =
        when (val read = withContext(io) { read(ref) }) {
            is Read.Key -> read.plain.toString(Charsets.UTF_8).also { read.plain.fill(0) }
            Read.Missing -> throw ModelError.Auth("no API key is stored for $ref; enter it again")
            Read.Unreadable -> throw ModelError.Auth("the API key stored for $ref cannot be read; enter it again")
        }

    private fun read(ref: SecretRef): Read =
        try {
            decoded(ref, fileOf(ref).readBytes())
        } catch (ignoredNoFile: FileNotFoundException) {
            // No key under this ref.
            Read.Missing
        } catch (ignoredUnreadable: IOException) {
            // The file is there but cannot be read: enter the key again, like any unreadable key.
            Read.Unreadable
        }

    private fun decoded(
        ref: SecretRef,
        bytes: ByteArray,
    ): Read = CredentialFormat.decode(bytes)?.let { opened(it, ref) } ?: Read.Unreadable

    private fun opened(
        sealed: Sealed,
        ref: SecretRef,
    ): Read =
        try {
            Read.Key(cipher.open(sealed, aad(ref)))
        } catch (ignoredUnreadable: CipherUnreadable) {
            // Reported as a status; its class-only message adds nothing for the user.
            Read.Unreadable
        }

    private fun fileOf(ref: SecretRef): File = File(dir, sha256Hex(ref.value) + SUFFIX)

    private fun aad(ref: SecretRef): ByteArray = (AAD_PREFIX + ref.value).toByteArray(Charsets.UTF_8)

    private sealed interface Read {
        class Key(
            val plain: ByteArray,
        ) : Read

        data object Missing : Read

        data object Unreadable : Read
    }
}

/** Whether a stored key can be used. */
internal enum class CredentialStatus {
    /** Stored and readable. */
    PRESENT,

    /** Nothing stored under the ref. */
    MISSING,

    /** Stored but cannot be decrypted (Keystore cleared, another device, damaged); enter it again. */
    UNREADABLE,
}

/** The bytes of one credential file. */
internal object CredentialFormat {
    private const val VERSION: Byte = 1
    private const val IV_BYTES = 12
    private const val TAG_BYTES = 16

    fun encode(sealed: Sealed): ByteArray {
        require(sealed.iv.size == IV_BYTES) { "AES-GCM IV must be $IV_BYTES bytes" }
        return byteArrayOf(VERSION) + sealed.iv + sealed.ciphertext
    }

    /** The sealed secret in [bytes], or `null` for an unknown version or a file too short to hold one. */
    fun decode(bytes: ByteArray): Sealed? =
        if (bytes.size < 1 + IV_BYTES + TAG_BYTES || bytes[0] != VERSION) {
            null
        } else {
            Sealed(bytes.copyOfRange(1, 1 + IV_BYTES), bytes.copyOfRange(1 + IV_BYTES, bytes.size))
        }
}

private fun sha256Hex(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0') }

private const val BYTE_MASK = 0xff
private const val HEX = 16

private const val AAD_PREFIX = "phoneagent-credential-v1:"
private const val SUFFIX = ".bin"

private val STORED_NAME = Regex("[0-9a-f]{64}\\.bin")
