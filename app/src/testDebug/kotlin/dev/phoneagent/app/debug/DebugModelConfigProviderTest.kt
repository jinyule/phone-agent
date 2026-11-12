package dev.phoneagent.app.debug

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.DEBUG_MODEL_AUTHORITY
import dev.phoneagent.app.model.settings.ModelSettingsFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowBinder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DebugModelConfigProviderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = Robolectric.setupContentProvider(DebugModelConfigProvider::class.java, DEBUG_MODEL_AUTHORITY)
    private val config get() = DebugModelConfig.of(context)

    private fun uri(path: String) = Uri.parse("content://$DEBUG_MODEL_AUTHORITY/$path")

    @After
    fun resetCaller() {
        ShadowBinder.reset()
    }

    @Test
    fun appsCanNeitherWriteNorReadAndChangeNothing() {
        // A state of its own, to see that the refused calls leave it alone.
        config.submit("{ not json".toByteArray())
        val before = config.status()
        ShadowBinder.setCallingUid(APP_UID)
        for ((path, mode) in listOf("config" to "w", "status" to "r", "keystore-selfcheck" to "r", "nowhere" to "r")) {
            assertThrows(path, SecurityException::class.java) { provider.openFile(uri(path), mode) }
        }
        assertEquals(before, config.status())
        assertFalse(File(context.filesDir, ModelSettingsFile.NAME).exists())
    }

    @Test
    fun theShellReadsTheStatus() {
        ShadowBinder.setCallingUid(DebugModelConfigProvider.SHELL_UID)
        val reply = provider.openFile(uri("status"), "r")
        assertEquals(config.status() + "\n", line(reply))
    }

    /**
     * The first line from [reply]. Robolectric's pipe is a temporary file, so a read that comes
     * before the provider's writer thread sees the end of the file: keep reading until the line ends.
     */
    private fun line(reply: ParcelFileDescriptor): String =
        ParcelFileDescriptor.AutoCloseInputStream(reply).use { input ->
            val out = ByteArrayOutputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READ_SECONDS)
            while (!out.toByteArray().toString(Charsets.UTF_8).endsWith("\n") && System.nanoTime() < deadline) {
                val next = input.read()
                if (next < 0) Thread.sleep(POLL_MILLIS) else out.write(next)
            }
            out.toByteArray().toString(Charsets.UTF_8)
        }

    /**
     * `receiving` is set before [DebugModelConfigProvider.openFile] returns, so the previous outcome
     * (`invalid`) is gone when `content write` returns. The provider's reader thread hands what it
     * read to [DebugModelConfig.submit], which takes the config's lock; holding that lock keeps the
     * reader from replacing the state, so the state read here is the one `openFile` left, whatever
     * the threads' timing.
     */
    @Test
    fun openingTheConfigHidesThePreviousOutcomeAtOnce() {
        ShadowBinder.setCallingUid(DebugModelConfigProvider.SHELL_UID)
        runBlocking { config.submit(INVALID.toByteArray())?.join() }
        val before = state()
        val after =
            synchronized(config) {
                provider.openFile(uri("config"), "w").close()
                state()
            }
        assertEquals("invalid" to "receiving", before to after)
    }

    private fun state() =
        Json
            .parseToJsonElement(config.status())
            .jsonObject
            .getValue("state")
            .jsonPrimitive.content

    @Test
    fun wrongModesAndPathsAreRefused() {
        ShadowBinder.setCallingUid(DebugModelConfigProvider.SHELL_UID)
        for ((path, mode) in listOf(
            "config" to "r",
            "config" to "rw",
            "status" to "w",
            "keystore-selfcheck" to "rw",
            "nowhere" to "r",
        )) {
            assertThrows("$path $mode", FileNotFoundException::class.java) { provider.openFile(uri(path), mode) }
        }
    }

    private companion object {
        const val INVALID = """{"baseUrl":"http://127.0.0.1:1/v1","auth":"none","models":{"main":""}}"""
        const val APP_UID = 10_123
        const val READ_SECONDS = 5L
        const val POLL_MILLIS = 10L
    }
}
