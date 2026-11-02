package dev.phoneagent.app.adapters.screen

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryLevelsTest {
    private val field =
        node("android.widget.EditText", Rect(0, 0, 1000, 100)) {
            isEditable = true
            text = "old"
        }

    private fun request(
        text: String,
        append: Boolean = false,
    ) = TextEntry.Request(text, append, before = "old")

    private class FakeClipboard(
        private val saved: SavedClip?,
        private val accepts: Boolean = true,
    ) : ClipboardAccess {
        val log = ArrayList<String>()

        override fun save(): SavedClip? = saved.also { log += "save" }

        override fun put(
            text: String,
            sensitive: Boolean,
        ): Boolean {
            log += "put:$text:$sensitive"
            return accepts
        }

        override fun restore(saved: SavedClip) {
            log += "restore"
        }
    }

    /** An input connection; [moves] says whether it can move the cursor, [lands] where a move ends. */
    private class FakeIme(
        override val editorPackage: String?,
        private val moves: Boolean = true,
        private val lands: (Int) -> Int = { it },
    ) : ImeConnection {
        val commits = ArrayList<Pair<String, Boolean>>()
        val selections = ArrayList<Pair<Int, Int>>()
        private var cursor: Pair<Int, Int>? = null

        override fun commit(
            text: String,
            replace: Boolean,
        ): Boolean {
            commits += text to replace
            return true
        }

        override fun performEditorAction(): Boolean = true

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean {
            if (!moves) return false
            selections += start to end
            cursor = lands(start) to lands(end)
            return true
        }

        override fun selection(): Pair<Int, Int>? = cursor
    }

    private fun selection(index: Int): Pair<Int, Int> {
        val args = field.log.actions[index].second!!
        return args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT) to
            args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT)
    }

    @Test
    fun setTextSendsWhatTheFieldShouldShow() =
        runTest {
            assertEquals(TextEntry.Sent.Delivered, EntryLevels.setText(field).send(request(" new", append = true)))
            val (action, args) = field.log.actions.single()
            assertEquals(AccessibilityNodeInfo.ACTION_SET_TEXT, action)
            assertEquals(
                "old new",
                args?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE).toString(),
            )

            field.log.answer = { _, _ -> false }
            assertEquals(TextEntry.Sent.Refused, EntryLevels.setText(field).send(request("x")))
        }

    @Test
    fun pasteIsSkippedWhenTheClipboardCannotBeRestored() =
        runTest {
            val clipboard = FakeClipboard(saved = null)

            val sent = EntryLevels.paste(field, clipboard) { error("not reached") }.send(request("x"))

            assertEquals(TextEntry.Sent.Skipped("the clipboard cannot be restored afterwards"), sent)
            assertEquals(listOf("save"), clipboard.log)
            assertTrue(field.log.actions.isEmpty())
        }

    @Test
    fun pasteReplacesTheSelectionAndRestoresTheClipboardAfterTheFieldChanged() =
        runTest {
            val clipboard = FakeClipboard(SavedClip(null))
            var waited = false

            val sent =
                EntryLevels
                    .paste(field, clipboard) {
                        assertEquals("the clip is held while waiting", listOf("save", "put:new:false"), clipboard.log)
                        waited = true
                    }.send(request("new"))

            assertEquals(TextEntry.Sent.Delivered, sent)
            assertTrue(waited)
            assertEquals(listOf("save", "put:new:false", "restore"), clipboard.log)
            assertEquals(
                listOf(AccessibilityNodeInfo.ACTION_SET_SELECTION, AccessibilityNodeInfo.ACTION_PASTE),
                field.log.ids,
            )
            assertEquals(0 to 3, selection(0))
        }

    @Test
    fun appendPastesAtTheEnd() =
        runTest {
            EntryLevels.paste(field, FakeClipboard(SavedClip(null))) {}.send(request("!", append = true))

            assertEquals(3 to 3, selection(0))
        }

    @Test
    fun theClipboardIsRestoredEvenWhenTheWaitIsCancelled() =
        runTest {
            val clipboard = FakeClipboard(SavedClip(null))

            assertThrows(CancellationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    EntryLevels
                        .paste(
                            field,
                            clipboard,
                        ) { throw CancellationException("task stopped") }
                        .send(request("x"))
                }
            }
            assertEquals("restore", clipboard.log.last())
        }

    @Test
    fun aRefusedClipboardOrPasteIsReported() =
        runTest {
            val refusing = FakeClipboard(SavedClip(null), accepts = false)
            assertEquals(
                TextEntry.Sent.Skipped("the clipboard is unavailable"),
                EntryLevels.paste(field, refusing) {}.send(request("x")),
            )
            assertEquals("restore", refusing.log.last())

            field.log.answer = { action, _ -> action != AccessibilityNodeInfo.ACTION_PASTE }
            var waited = false
            assertEquals(
                TextEntry.Sent.Refused,
                EntryLevels.paste(field, FakeClipboard(SavedClip(null))) { waited = true }.send(request("x")),
            )
            assertEquals(false, waited)
        }

    @Test
    fun passwordsAreMarkedSensitiveOnTheClipboard() =
        runTest {
            field.isPassword = true
            val clipboard = FakeClipboard(SavedClip(null))

            EntryLevels.paste(field, clipboard) {}.send(request("1234"))

            assertTrue("put:1234:true" in clipboard.log)
        }

    @Test
    fun theInputConnectionNeedsTheFocusedFieldOfItsApp() =
        runTest {
            val host = FakeHost()
            var focusRequests = 0
            val level = EntryLevels.imeConnection(field, host) { focusRequests++ }

            assertEquals(TextEntry.Sent.Skipped("no input connection"), level.send(request("x")))
            assertEquals(1, focusRequests)

            host.ime = FakeIme(editorPackage = APP)
            assertEquals(TextEntry.Sent.Skipped("the field does not hold input focus"), level.send(request("x")))

            field.isFocused = true
            host.ime = FakeIme(editorPackage = "com.other")
            assertEquals(TextEntry.Sent.Skipped("the field does not hold input focus"), level.send(request("x")))
            assertEquals("no focus request once the field holds focus", 2, focusRequests)
        }

    @Test
    fun theInputConnectionReplacesOrAppends() =
        runTest {
            field.isFocused = true
            val ime = FakeIme(editorPackage = APP)
            val host = FakeHost().apply { this.ime = ime }
            val level = EntryLevels.imeConnection(field, host) { error("already focused") }

            assertEquals(TextEntry.Sent.Delivered, level.send(request("new")))
            assertEquals(TextEntry.Sent.Delivered, level.send(request("!", append = true)))

            assertEquals(listOf("new" to true, "!" to false), ime.commits)
            assertEquals("the cursor is moved through the connection", listOf(3 to 3), ime.selections)
            assertTrue(field.log.ids.isEmpty())
        }

    @Test
    fun theInputConnectionCommitsOnlyWhereTheCursorWasConfirmed() =
        runTest {
            // Claude r1 x4: the app keeps the cursor where it was; " world" would land in the middle.
            field.isFocused = true
            val ime = FakeIme(editorPackage = APP, lands = { 1 })
            val host = FakeHost().apply { this.ime = ime }

            val sent =
                EntryLevels
                    .imeConnection(
                        field,
                        host,
                    ) { error("already focused") }
                    .send(request("!", append = true))

            assertEquals(TextEntry.Sent.Skipped("cannot place the cursor"), sent)
            assertTrue(ime.commits.isEmpty())
        }

    @Test
    fun withoutAConnectionCursorTheNodePlacesItAndReportsWhereItIs() =
        runTest {
            field.isFocused = true
            field.log.answer = { action, args ->
                if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
                    val start = args!!.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT)
                    field.setTextSelection(start, args.getInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT))
                }
                true
            }
            val ime = FakeIme(editorPackage = APP, moves = false)
            val host = FakeHost().apply { this.ime = ime }
            val level = EntryLevels.imeConnection(field, host) { error("already focused") }

            assertEquals(TextEntry.Sent.Delivered, level.send(request("!", append = true)))
            assertEquals(3 to 3, selection(0))
            assertEquals(listOf("!" to false), ime.commits)

            // A node that does not report its selection cannot confirm the cursor.
            field.log.answer = { _, _ -> true }
            field.setTextSelection(-1, -1)
            assertEquals(TextEntry.Sent.Skipped("cannot place the cursor"), level.send(request("!", append = true)))
        }

    @Test
    fun readBackShowsTheFieldOrThatItIsGone() {
        assertEquals(TextEntry.Shown.Text("old"), TextEntry.read(field))

        field.hintText = "Search"
        field.text = "Search"
        assertEquals("a hint is not content", TextEntry.Shown.Text(""), TextEntry.read(field))

        field.isPassword = true
        field.isShowingHintText = true
        field.text = "Password"
        assertEquals("a password field showing its hint is empty", TextEntry.Shown.Text(""), TextEntry.read(field))
        field.isShowingHintText = false
        field.hintText = null
        field.text = "••••"
        assertEquals(TextEntry.Shown.Text("••••"), TextEntry.read(field))

        org.robolectric.Shadows
            .shadowOf(field)
            .setRefreshReturnValue(false)
        assertEquals(TextEntry.Shown.Gone, TextEntry.read(field))
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidClipboardTest {
    private val manager =
        ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(
            ClipboardManager::class.java,
        )
    private val clipboard = AndroidClipboard(manager)

    @Test
    fun anUnreadableClipboardCannotBeSaved() {
        assertNull(AndroidClipboard(null).save())
        assertEquals(false, AndroidClipboard(null).put("x", sensitive = false))
    }

    @Test
    fun putsAndRestoresTheUsersClip() {
        manager.setPrimaryClip(ClipData.newPlainText("user", "mine"))
        val saved = clipboard.save()!!

        assertTrue(clipboard.put("secret", sensitive = true))
        val held = manager.primaryClip!!
        assertEquals("secret", held.getItemAt(0).text.toString())
        assertEquals(true, held.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))

        clipboard.restore(saved)
        assertEquals(
            "mine",
            manager.primaryClip!!
                .getItemAt(0)
                .text
                .toString(),
        )
    }

    @Test
    fun restoringAnEmptyClipboardClearsIt() {
        manager.setPrimaryClip(ClipData.newPlainText("user", "mine"))
        clipboard.put("x", sensitive = false)

        clipboard.restore(SavedClip(null))

        assertNull(manager.primaryClip)
    }
}
