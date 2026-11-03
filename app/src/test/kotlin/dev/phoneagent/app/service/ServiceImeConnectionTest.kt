package dev.phoneagent.app.service

import android.accessibilityservice.InputMethod
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.adapters.screen.APP
import dev.phoneagent.app.adapters.screen.EntryLevels
import dev.phoneagent.app.adapters.screen.FakeHost
import dev.phoneagent.app.adapters.screen.TextEntry
import dev.phoneagent.app.adapters.screen.node
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** The service's input method, bound to [ConnectionShadow]'s editor of this app. */
@Implements(InputMethod::class, minSdk = 33)
class InputMethodShadow {
    @Implementation
    fun getCurrentInputConnection(): InputMethod.AccessibilityInputConnection? = connection

    @Implementation
    fun getCurrentInputEditorInfo(): EditorInfo = EditorInfo().also { it.packageName = APP }

    companion object {
        var connection: InputMethod.AccessibilityInputConnection? = null
    }
}

/**
 * An editor holding [content] that answers `getSurroundingText` with [around] (what a real
 * editor would say after select-all) and commits over [selected], a range of [content].
 */
@Implements(InputMethod.AccessibilityInputConnection::class, minSdk = 33)
class ConnectionShadow {
    @Implementation
    fun performContextMenuAction(
        @Suppress("UNUSED_PARAMETER") id: Int,
    ) {
        selectAllAsked = true
    }

    @Implementation
    fun getSurroundingText(
        @Suppress("UNUSED_PARAMETER") beforeLength: Int,
        @Suppress("UNUSED_PARAMETER") afterLength: Int,
        @Suppress("UNUSED_PARAMETER") flags: Int,
    ): SurroundingText? = around

    @Implementation
    fun commitText(
        text: CharSequence,
        @Suppress("UNUSED_PARAMETER") newCursorPosition: Int,
        @Suppress("UNUSED_PARAMETER") textAttribute: TextAttribute?,
    ) {
        content = content.substring(0, selected.first) + text + content.substring(selected.last + 1)
    }

    companion object {
        var content = ""
        var selected = IntRange.EMPTY
        var around: SurroundingText? = null
        var selectAllAsked = false
    }
}

/**
 * [ServiceImeConnection.selectAll] as built, against the platform's input connection with only
 * the editor's answers replaced (fix list r4 S1): all is selected only when the editor's text
 * around the selection shows nothing before it from the very start, and nothing after it.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [InputMethodShadow::class, ConnectionShadow::class])
class ServiceImeConnectionTest {
    private lateinit var ime: ServiceImeConnection

    @Before
    fun bind() {
        val service = Robolectric.buildService(AgentAccessibilityService::class.java).create().get()
        val method = InputMethod(service)
        // The connection's constructor is hidden; the shadow answers for it.
        val constructor =
            InputMethod.AccessibilityInputConnection::class.java.declaredConstructors.single { it.parameterCount == 2 }
        constructor.isAccessible = true
        InputMethodShadow.connection = constructor.newInstance(method, null) as InputMethod.AccessibilityInputConnection
        ConnectionShadow.selectAllAsked = false
        ime = ServiceImeConnection(method)
    }

    /** The password "oldpw", of which the editor selected [selected] and reports [around]. */
    private fun editor(
        selected: IntRange,
        around: SurroundingText?,
    ) {
        ConnectionShadow.content = "oldpw"
        ConnectionShadow.selected = selected
        ConnectionShadow.around = around
    }

    @Test
    fun allOfItIsSelectedOnlyWhenNothingIsBeforeOrAfterTheSelection() {
        editor(0..4, SurroundingText("oldpw", 0, 5, 0))
        assertEquals(true, ime.selectAll())
        assertEquals("select-all was asked for", true, ConnectionShadow.selectAllAsked)

        // Codex r4 S1: only "pw" selected; the text around it starts at offset 3, after "old".
        editor(3..4, SurroundingText("pw", 0, 2, 3))
        assertEquals("a selection from offset 3", false, ime.selectAll())

        editor(0..3, SurroundingText("oldpw", 0, 4, 0))
        assertEquals("a selection that stops before the end", false, ime.selectAll())

        editor(1..4, SurroundingText("oldpw", 1, 5, 0))
        assertEquals("a selection after the first character", false, ime.selectAll())
    }

    @Test
    fun whatTheEditorDoesNotTellIsUnknown() {
        editor(0..4, SurroundingText("oldpw", 0, 5, -1))
        assertNull("the text's offset unknown", ime.selectAll())

        editor(0..4, null)
        assertNull("no text around the selection", ime.selectAll())

        InputMethodShadow.connection = null
        assertNull("no connection", ime.selectAll())
    }

    @Test
    fun aPasswordOnlyPartlySelectedIsNotTypedOver() =
        runTest {
            // Codex r4 S1, end to end through the input-connection level: the editor ignored select-all
            // and kept "pw" selected.
            editor(3..4, SurroundingText("pw", 0, 2, 3))
            val field =
                node("android.widget.EditText", Rect(0, 0, 1000, 100)) {
                    isEditable = true
                    isFocused = true
                    isPassword = true
                }
            val host = FakeHost().also { it.ime = ime }

            val sent =
                EntryLevels
                    .imeConnection(field, host) { error("the field holds focus") }
                    .send(TextEntry.Request("newpw", append = false, before = null))

            assertEquals(TextEntry.Sent.Skipped("cannot place the cursor"), sent)
            assertEquals("oldpw", ConnectionShadow.content)
        }
}
