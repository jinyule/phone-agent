/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/adb/AndroidAdb.kt and the WrappedKeyFile
 * class in core/src/main/kotlin/com/wangzan101/mika/core/adb/AdbIdentity.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: English documentation; the wrap is an interface so the file
 * logic is tested without a keystore; a format byte and a fixed 12-byte IV instead of an IV length;
 * AtomicFile writes; an unopenable key reads as unusable (replaced) instead of absent.
 */
package dev.phoneagent.app.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import dev.phoneagent.core.adb.AdbKeyStore
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals the stored adb key and opens it again; both throw [GeneralSecurityException] when they
 * cannot, or [ProviderException] for a failure inside the Android Keystore (it reports those
 * unchecked).
 */
internal interface KeyWrap {
    /** [plain] encrypted and authenticated, IV included. */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun seal(plain: ByteArray): ByteArray

    /** What [seal] was given; [GeneralSecurityException] when [sealed] is not what it returned or its key is gone. */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun open(sealed: ByteArray): ByteArray
}

/**
 * The adb private key (PKCS#8), sealed by [wrap], in [file]: app-private storage that is never
 * backed up or transferred (`noBackupFilesDir`). The file is a format byte followed by what
 * [KeyWrap.seal] returned, written through [AtomicFile], so a crash leaves the old key or the new
 * one. A file that cannot be opened (the keystore key was lost in a reset, or the file is damaged)
 * reads as unusable bytes: `AdbIdentity.loadOrCreate` then replaces the identity and reports
 * `REPLACED`, and the device has to be paired again. A key that cannot be sealed fails [write] with
 * [IOException] and leaves the file as it was. Checked and Keystore-internal failures of [wrap] are
 * treated alike. A key that did not reach the file fails [write] with [IOException] too: the file is
 * read back, since `AtomicFile.finishWrite` only logs a failed sync, close or rename.
 */
internal class SealedFileKeyStore(
    private val file: File,
    private val wrap: KeyWrap,
    /** Makes the [AtomicFile] of [file]; tests make it fail. */
    private val atomicFile: (File) -> AtomicFile = ::AtomicFile,
) : AdbKeyStore {
    override fun read(): ByteArray? = readOrNull()?.let(::unseal)

    override fun write(pkcs8: ByteArray) {
        val bytes = byteArrayOf(FORMAT) + seal(pkcs8)
        file.parentFile?.mkdirs()
        val atomic = atomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
        } catch (failed: IOException) {
            // Keeps the previous key, if any: a half-written file must never replace it.
            atomic.failWrite(out)
            throw failed
        }
        atomic.finishWrite(out)
        requireStored(atomic, bytes)
    }

    /** Throws [IOException] unless [atomic] holds [bytes]. */
    private fun requireStored(
        atomic: AtomicFile,
        bytes: ByteArray,
    ) {
        val stored =
            try {
                atomic.readFully()
            } catch (unreadable: IOException) {
                // The rename failed and nothing is there, or not a file.
                throw IOException("the adb key was not stored", unreadable)
            }
        if (!stored.contentEquals(bytes)) throw IOException("the adb key was not stored: the file holds other bytes")
    }

    private fun readOrNull(): ByteArray? =
        try {
            atomicFile(file).readFully()
        } catch (expectedAbsent: FileNotFoundException) {
            // Nothing stored yet: the first identity is created and written.
            null
        }

    private fun unseal(stored: ByteArray): ByteArray =
        if (stored.isEmpty() || stored[0] != FORMAT) UNUSABLE else open(stored.copyOfRange(1, stored.size))

    private fun open(sealed: ByteArray): ByteArray =
        try {
            wrap.open(sealed)
        } catch (unopenable: GeneralSecurityException) {
            // The key cannot be recovered; replacing it is the only way on.
            unusable(unopenable)
        } catch (keystoreInternal: ProviderException) {
            // The Keystore's own failures arrive unchecked; the same as a checked refusal.
            unusable(keystoreInternal)
        }

    private fun unusable(cause: Exception): ByteArray {
        Log.w(TAG, "the stored adb key cannot be opened; a new identity replaces it", cause)
        return UNUSABLE
    }

    private fun seal(pkcs8: ByteArray): ByteArray =
        try {
            wrap.seal(pkcs8)
        } catch (failed: GeneralSecurityException) {
            throw IOException("cannot seal the adb key", failed)
        } catch (keystoreInternal: ProviderException) {
            // The Keystore's own failures arrive unchecked; the same as a checked refusal.
            throw IOException("cannot seal the adb key", keystoreInternal)
        }

    private companion object {
        const val TAG = "PhoneAgentBuiltinAdb"

        /** Version of the file layout. */
        const val FORMAT: Byte = 1

        /** No PKCS#8 key is empty, so the identity is replaced. */
        val UNUSABLE = ByteArray(0)
    }
}

/**
 * AES-256-GCM under a key that never leaves the device keystore (`AndroidKeyStore`, alias
 * [alias]), created on the first [seal]. Only works on a device: Robolectric has no keystore.
 */
internal class KeystoreKeyWrap(
    private val alias: String = DEFAULT_ALIAS,
) : KeyWrap {
    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: newKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val key = existingKey() ?: throw UnrecoverableKeyException("the keystore holds no key '$alias'")
        if (sealed.size <= IV_BYTES) throw UnrecoverableKeyException("a sealed key of ${sealed.size} bytes")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance(PROVIDER).apply { load(null) }.getKey(alias, null) as? SecretKey

    private fun newKey(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val DEFAULT_ALIAS = "phoneagent_adb_key_wrap"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
