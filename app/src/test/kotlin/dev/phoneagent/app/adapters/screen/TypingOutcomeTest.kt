package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.Bounds
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * What typing reports when the field does not end up showing the request (fix list r1 B5), how
 * password fields are appended to (B7), and a clipboard that could not be restored (S3).
 */
@RunWith(AndroidJUnit4::class)
class TypingOutcomeTest {
    private val host = FakeHost()
    private val bounds = Rect(0, 200, 1000, 300)

    private fun field(setup: AccessibilityNodeInfo.() -> Unit = {}): AccessibilityNodeInfo =
        node("android.widget.EditText", bounds) {
            isEditable = true
            isFocused = true
            text = ""
            contentDescription = "Amount"
            addAction(AccessibilityAction.ACTION_IME_ENTER)
            setup()
        }

    private val setTexts: (AccessibilityNodeInfo) -> List<String> = { node ->
        node.log.actions
            .filter { it.first == AccessibilityNodeInfo.ACTION_SET_TEXT }
            .map {
                it.second
                    ?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                    .toString()
            }
    }

    /**
     * Types into [field] itself. Robolectric hands out copies of nodes from a window, and copies do
     * not see what the app writes into the original, so the run is aimed at the original node.
     */
    private suspend fun TestScope.typeInto(
        field: AccessibilityNodeInfo,
        action: UiAction.Type,
        system: TestSystem = TestSystem(),
    ): ActionOutcome {
        host.show(field)
        val analyzer = RefDiffAnalyzer()
        val surface = SurfaceRef(0, SurfaceKind.MAIN)
        val observer =
            ScreenObserver(surface, system, analyzer, AndroidScreen.sequentialSnapshotIds(), SnapshotHistory(keep = 8))
        val parts = PortParts(surface, system, analyzer, observer, ScreenTimings(), injector = null)
        val element = UiElement(ElementRef("e1"), ElementRole.INPUT, bounds = Bounds(0, 100, 999, 150))
        val identity = ElementIdentity(1, ElementRole.INPUT, resourceId = null, name = "Amount", context = null)
        val read =
            ReadElement(
                element,
                NodeTarget(field, Rect(bounds)),
                NodePlace(1, APP, parent = null, depth = 0),
                true,
                identity,
            )
        val aim = Aim.AtElement(read)
        val baseline = observer.baseline(host, GEOMETRY, SnapshotDetail.BRIEF)
        return ActionRun(parts, host, GEOMETRY, baseline, compareWith = null).perform(action, aim)
    }

    /** A readable clipboard that records every call, so a test can assert it was never touched. */
    private class WatchedClipboard : ClipboardAccess {
        val calls = ArrayList<String>()

        override fun save(): SavedClip = SavedClip(null).also { calls += "save" }

        override fun put(
            text: String,
            sensitive: Boolean,
        ): Boolean = true.also { calls += "put" }

        override fun restore(saved: SavedClip): Boolean = true.also { calls += "restore" }
    }

    @Test
    fun aFieldThatAlreadyShowsTheRequestIsSubmittedWithoutTouchingItOrTheClipboard() =
        runTest {
            // Fix list r2 B2 (Claude r2 B2 / Codex R2-B3): already right, as shown, as formatted, or empty.
            // The formatted one is a phone field (fix list r3 S1): only there does formatting count.
            for ((shows, typed) in listOf("hello" to "hello", "555-1234" to "5551234", "" to "")) {
                val field =
                    field {
                        text = shows
                        if (shows.contains('-')) inputType = InputType.TYPE_CLASS_PHONE
                    }
                val clipboard = WatchedClipboard()
                val system = TestSystem().apply { this.clipboard = clipboard }

                val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), typed, submit = true), system)

                assertEquals("'$shows' for '$typed'", ActionOutcome.Status.Ok, outcome.status)
                assertEquals("submitted, nothing else", listOf(AccessibilityAction.ACTION_IME_ENTER.id), field.log.ids)
                assertEquals("the clipboard is not touched", emptyList<String>(), clipboard.calls)
            }
        }

    @Test
    fun aFieldOtherThanANumberThatDiffersOnlyInSpacesOrDashesIsTypedInto() =
        runTest {
            // Fix list r3 S1 (Claude r3 S1): before typing, the app has formatted nothing yet; a
            // text field that does not show exactly the request needs it typed.
            val table =
                listOf(
                    Triple("NewYork", "New York", false),
                    Triple("helloworld", "hello world", false),
                    Triple("co-op", "coop", false),
                    Triple("hello", " ", true),
                    Triple("", " ", false),
                )
            for ((shows, typed, append) in table) {
                val field = field { text = shows }
                field.log.answer = { action, args ->
                    if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                        field.text = args!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)
                    }
                    true
                }
                val expected = if (append) shows + typed else typed

                typeInto(field, UiAction.Type(Target.Point(500, 125), typed, append = append, submit = true))

                val submitted = AccessibilityAction.ACTION_IME_ENTER.id in field.log.ids
                assertTrue(
                    "'$shows' for '$typed': shows '${field.text}', submitted $submitted",
                    field.text?.toString() == expected || !submitted,
                )
            }
        }

    @Test
    fun aFieldThatShowsOtherTextFailsWithWhatItShowsAndIsNotSubmitted() =
        runTest {
            // Codex r1 B6 / Claude x3c: the field takes four digits only.
            val field = field()
            field.log.answer = { action, args ->
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    val typed = args!!.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)!!
                    field.text = typed.take(4)
                }
                true
            }

            val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "123456", submit = true))

            val status = outcome.status as ActionOutcome.Status.Failed
            assertTrue(status.reason, status.reason.contains("shows '1234'"))
            assertFalse("not submitted", AccessibilityAction.ACTION_IME_ENTER.id in field.log.ids)
        }

    @Test
    fun aFieldThatWasReplacedWhileTypingIsNeitherReportedTypedNorSubmitted() =
        runTest {
            val field = field()
            field.log.answer = { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) shadowOf(field).setRefreshReturnValue(false)
                true
            }

            val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "123456", submit = true))

            assertTrue(outcome.status is ActionOutcome.Status.Failed)
            assertFalse(AccessibilityAction.ACTION_IME_ENTER.id in field.log.ids)
        }

    @Test
    fun formattingTheFieldAddsStillCountsAsTheRequestAndIsSubmitted() =
        runTest {
            val field = field()
            field.log.answer = { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) field.text = "555-1234"
                true
            }

            val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "5551234", submit = true))

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertTrue(AccessibilityAction.ACTION_IME_ENTER.id in field.log.ids)
        }

    @Test
    fun aPasswordThatCannotBeReadBackTrustsTheFirstLevelAndIsSubmitted() =
        runTest {
            val field = field { isPassword = true }

            val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "hunter2", submit = true))

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals(listOf("hunter2"), setTexts(field))
            assertTrue(AccessibilityAction.ACTION_IME_ENTER.id in field.log.ids)
        }

    @Test
    fun appendingToAMaskedPasswordNeverSendsTheMask() =
        runTest {
            // Claude r1 p4 / Codex r1 B7.
            val field =
                field {
                    isPassword = true
                    text = "••••"
                }

            val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "abc", append = true))

            assertTrue(setTexts(field).none { it.contains("•") })
            assertEquals(ActionOutcome.Status.Failed("a password field can only be typed as a whole"), outcome.status)
        }

    /**
     * An editor behind the input connection that holds the real [content], which accessibility does
     * not show for a password, and moves its [cursor] honestly (out-of-range moves are ignored, as
     * `BaseInputConnection` does).
     */
    private class Editor(
        var content: String,
        var cursor: Int,
    ) : ImeConnection {
        override val editorPackage: String = APP

        override fun commit(
            text: String,
            replace: Boolean,
        ): Boolean {
            if (replace) content = "".also { cursor = 0 }
            content = content.substring(0, cursor) + text + content.substring(cursor)
            cursor += text.length
            return true
        }

        override fun performEditorAction(): Boolean = true

        override fun setSelection(
            start: Int,
            end: Int,
        ): Boolean {
            if (start in 0..content.length && start == end) cursor = start
            return true
        }

        override fun selection(): Pair<Int, Int> = cursor to cursor

        override fun cursorAtEnd(): Boolean = cursor == content.length
    }

    @Test
    fun appendingToAPasswordGoesOnlyAtTheEndTheEditorConfirms() =
        runTest {
            // Fix list r2 B3 (Codex R2-B4): the password is "secret"; accessibility shows nothing, or a
            // mask shorter than the password. Neither length is where the password ends.
            for (shows in listOf(null, "••••")) {
                val field =
                    field {
                        isPassword = true
                        text = shows
                    }
                val editor = Editor("secret", cursor = 0)
                host.ime = editor

                val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "abc", append = true))

                assertEquals("$shows", ActionOutcome.Status.Failed(TextEntry.WHOLE_PASSWORD), outcome.status)
                assertEquals("$shows", "secret", editor.content)
            }

            // Where the editor itself says its cursor is at the end, the text goes there.
            val field =
                field {
                    isPassword = true
                    text = null
                }
            val editor = Editor("secret", cursor = 6)
            host.ime = editor

            val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "abc", append = true))

            assertEquals(ActionOutcome.Status.Ok, outcome.status)
            assertEquals("secretabc", editor.content)
        }

    @Test
    fun appendingToAnEmptyPasswordFieldIgnoresItsHint() =
        runTest {
            val field =
                field {
                    isPassword = true
                    text = "Password"
                    isShowingHintText = true
                }

            typeInto(field, UiAction.Type(Target.Point(500, 125), "abc", append = true))

            assertEquals(listOf("abc"), setTexts(field))
        }

    @Test
    fun aPasteThatCannotGiveTheClipboardBackSaysSoAndIsNotSubmitted() =
        runTest {
            // Claude r1 x7 / fix list S3: the text is in, the user's clip is not back.
            val field = field()
            field.log.answer = { action, _ ->
                if (action == AccessibilityNodeInfo.ACTION_PASTE) field.text = "Bye"
                action != AccessibilityNodeInfo.ACTION_SET_TEXT
            }
            val system = TestSystem()
            system.clipboard =
                object : ClipboardAccess {
                    override fun save(): SavedClip = SavedClip(null)

                    override fun put(
                        text: String,
                        sensitive: Boolean,
                    ): Boolean = true

                    override fun restore(saved: SavedClip): Boolean = false
                }

            val outcome = typeInto(field, UiAction.Type(Target.Point(500, 125), "Bye", submit = true), system)

            assertEquals(
                ActionOutcome.Status.Failed(
                    "the text was entered, but the user's clipboard could not be restored; it was not submitted",
                ),
                outcome.status,
            )
            assertFalse(AccessibilityAction.ACTION_IME_ENTER.id in field.log.ids)
        }
}
