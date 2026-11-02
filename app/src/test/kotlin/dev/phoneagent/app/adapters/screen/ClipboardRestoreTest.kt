package dev.phoneagent.app.adapters.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowClipboardManager

/**
 * A clipboard whose writes of a clip fail while [failing] (e.g. a content URI the app may not
 * grant). Clearing still works (Robolectric clears by setting `null`).
 */
@Implements(ClipboardManager::class)
class FailingClipboard : ShadowClipboardManager() {
    @Implementation
    override fun setPrimaryClip(clip: ClipData?) {
        if (failing && clip != null) throw SecurityException("Permission Denial: content URI")
        super.setPrimaryClip(clip)
    }

    companion object {
        var failing = false
    }
}

/** The clipboard level reports, never throws, when the user's clip cannot be put back (fix list r1 S3). */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [FailingClipboard::class])
class ClipboardRestoreTest {
    private val field =
        node("android.widget.EditText", Rect(0, 0, 1000, 100)) {
            isEditable = true
            isFocused = true
            text = "Hello"
        }

    private open class Clipboard : ClipboardAccess {
        override fun save(): SavedClip = SavedClip(null)

        override fun put(
            text: String,
            sensitive: Boolean,
        ): Boolean = true

        override fun restore(saved: SavedClip): Boolean = true
    }

    @After
    fun heal() {
        FailingClipboard.failing = false
    }

    @Test
    fun aCancelledPasteStaysCancelledWhenRestoringFails() =
        runTest {
            // Claude x7: the wait is cancelled and putting the user's clip back throws.
            val clipboard =
                object : Clipboard() {
                    override fun restore(saved: SavedClip) = throw SecurityException("content URI")
                }

            val error =
                failure {
                    EntryLevels
                        .paste(
                            field,
                            clipboard,
                        ) { throw CancellationException("stop") }
                        .send(TextEntry.Request("Bye", false, ""))
                }

            assertTrue("$error", error is CancellationException)
            assertTrue(error!!.suppressed.any { it is SecurityException })
        }

    @Test
    fun aClipThatCannotBePutBackIsReportedAndOursIsCleared() {
        val manager =
            ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(
                ClipboardManager::class.java,
            )
        manager.setPrimaryClip(ClipData.newPlainText("user", "theirs"))
        val clipboard = AndroidClipboard(manager)
        val saved = clipboard.save()!!
        clipboard.put("secret", sensitive = true)
        FailingClipboard.failing = true

        val restored = runCatching { clipboard.restore(saved) }

        assertEquals("reported, not thrown", false, restored.getOrNull())
        assertFalse("our text does not stay", manager.primaryClip?.getItemAt(0)?.text == "secret")
    }

    /** What [block] throws, or `null`. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun failure(block: suspend () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (thrown: Throwable) {
            // The test inspects whatever came out, cancellation included.
            thrown
        }
}
