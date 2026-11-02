package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The text-entry levels do not damage a field when they cannot place the cursor (fix list r1 B6).
 */
@RunWith(AndroidJUnit4::class)
class EntryLevelsSafetyTest {
    private val field =
        node("android.widget.EditText", Rect(0, 0, 1000, 100)) {
            isEditable = true
            isFocused = true
            text = "Hello"
        }

    private class Ime : ImeConnection {
        val commits = ArrayList<Pair<String, Boolean>>()
        override val editorPackage: String = APP

        override fun commit(
            text: String,
            replace: Boolean,
        ): Boolean {
            commits += text to replace
            return true
        }

        override fun performEditorAction(): Boolean = true

        // The connection cannot move the cursor; the node is asked instead.
        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean = false

        override fun selection(): Pair<Int, Int>? = null
    }

    private open class Clipboard : ClipboardAccess {
        val log = ArrayList<String>()

        override fun save(): SavedClip = SavedClip(null).also { log += "save" }

        override fun put(
            text: String,
            sensitive: Boolean,
        ): Boolean = true.also { log += "put:$text" }

        override fun restore(saved: SavedClip): Boolean {
            log += "restore"
            return true
        }
    }

    @Test
    fun theInputConnectionDoesNotCommitWhenTheCursorCannotBePlaced() =
        runTest {
            // Claude x4: ACTION_SET_SELECTION refused; " world" would land wherever the cursor is.
            field.log.answer = { action, _ -> action != AccessibilityNodeInfo.ACTION_SET_SELECTION }
            val ime = Ime()
            val host = FakeHost().apply { this.ime = ime }

            val sent =
                EntryLevels
                    .imeConnection(field, host) { error("already focused") }
                    .send(TextEntry.Request(" world", append = true, before = "Hello"))

            assertEquals(TextEntry.Sent.Skipped("cannot place the cursor"), sent)
            assertTrue(ime.commits.isEmpty())
        }

    @Test
    fun pasteDoesNotPasteWhenTheSelectionCannotBeSet() =
        runTest {
            field.log.answer = { action, _ -> action != AccessibilityNodeInfo.ACTION_SET_SELECTION }
            val clipboard = Clipboard()

            val sent =
                EntryLevels
                    .paste(
                        field,
                        clipboard,
                    ) {}
                    .send(TextEntry.Request("Bye", append = false, before = "Hello"))

            assertEquals(TextEntry.Sent.Skipped("cannot place the cursor"), sent)
            assertFalse(AccessibilityNodeInfo.ACTION_PASTE in field.log.ids)
            assertTrue(
                "nothing of ours is left on the clipboard",
                clipboard.log.none { it.startsWith("put") } || "restore" in clipboard.log,
            )
        }
}
