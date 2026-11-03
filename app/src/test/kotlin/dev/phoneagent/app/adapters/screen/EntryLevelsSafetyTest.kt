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
import org.robolectric.Shadows.shadowOf

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

        override fun cursorAtEnd(): Boolean? = null

        override fun selectAll(): Boolean? = null
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

    @Test
    fun pasteDoesNotPasteWhereTheSelectionDidNotGo() =
        runTest {
            // Fix list r2 S3 (Codex R2-S1): the editor says yes to SET_SELECTION, the cursor stays at 0.
            field.setTextSelection(0, 0)
            for (request in listOf(
                TextEntry.Request(" world", append = true, before = "Hello"),
                TextEntry.Request("Bye", append = false, before = "Hello"),
            )) {
                val clipboard = Clipboard()

                val sent = EntryLevels.paste(field, clipboard) {}.send(request)

                assertEquals(request.text, TextEntry.Sent.Skipped("cannot place the cursor"), sent)
                assertFalse(request.text, AccessibilityNodeInfo.ACTION_PASTE in field.log.ids)
                assertTrue(request.text, clipboard.log.none { it.startsWith("put") })
            }
        }

    @Test
    fun pasteDoesNotTrustASelectionItCouldNotReadAgain() =
        runTest {
            // Fix list r3 S3 (Codex r3 S2): SET_SELECTION says yes, then the node cannot be refreshed;
            // the selection it still holds is from before.
            field.setTextSelection(0, 5)
            shadowOf(field).setRefreshReturnValue(false)
            val clipboard = Clipboard()

            val request = TextEntry.Request("Bye", append = false, before = "Hello")

            val sent = EntryLevels.paste(field, clipboard) {}.send(request)

            assertEquals(TextEntry.Sent.Skipped("cannot place the cursor"), sent)
            assertFalse(AccessibilityNodeInfo.ACTION_PASTE in field.log.ids)
            assertTrue(clipboard.log.none { it.startsWith("put") })
        }

    @Test
    fun pasteDoesNotReplaceAPasswordWhoseLengthItCannotKnow() =
        runTest {
            // Fix list r3 S2 (Claude r3 S2): the password is "oldpw"; accessibility shows nothing, or a
            // mask shorter than it. Selecting that many characters would paste beside the rest.
            for (shows in listOf(null, "••••")) {
                val password =
                    node("android.widget.EditText", Rect(0, 0, 1000, 100)) {
                        isEditable = true
                        isFocused = true
                        isPassword = true
                        text = shows
                    }
                password.log.answer = { action, args ->
                    if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
                        password.setTextSelection(
                            args!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT),
                            args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT),
                        )
                    }
                    true
                }
                val clipboard = Clipboard()
                val request = TextEntry.Request("newpw", append = false, before = shows)

                val sent = EntryLevels.paste(password, clipboard) {}.send(request)

                assertEquals("shows $shows", TextEntry.Sent.Skipped("cannot place the cursor"), sent)
                assertFalse("shows $shows", AccessibilityNodeInfo.ACTION_PASTE in password.log.ids)
                assertTrue("shows $shows", clipboard.log.none { it.startsWith("put") })
            }
        }
}
