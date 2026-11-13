package dev.phoneagent.app.adapters.screen

import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The texts of a window the port gives core, as far as core's test of a withheld bank page
 * (`AssembledFlowsTest`) marks them: a content description arrives as an element's name, on a
 * control and on a field; the window title arrives nowhere (a window-state event is reduced to
 * its package and class, [A11yEvent]).
 */
@RunWith(AndroidJUnit4::class)
class TextSourcesTest {
    private val host = FakeHost()

    @Test
    fun aContentDescriptionArrivesAsTheNameAndTheWindowTitleNowhere() =
        runTest {
            val icon =
                node("android.widget.ImageButton", Rect(0, 100, 200, 200)) {
                    isClickable = true
                    contentDescription = "MARK_ICON"
                }
            val field =
                node("android.widget.EditText", Rect(0, 300, 1000, 400)) {
                    isEditable = true
                    isFocusable = true
                    isClickable = true
                    contentDescription = "MARK_FIELD"
                    text = "500"
                }
            host.show(icon, field)
            val window = host.windows.getValue(0).single()
            shadowOf(window).setTitle("MARK_TITLE")
            assertEquals("the window has the title", "MARK_TITLE", window.title)
            val port = mainPort(host)

            for (detail in SnapshotDetail.entries) {
                val snapshot = port.snapshot(SnapshotOptions(detail = detail))
                val named = snapshot.elements.filter { it.name?.startsWith("MARK_") == true }
                assertEquals(
                    "$detail",
                    listOf(ElementRole.BUTTON to "MARK_ICON", ElementRole.INPUT to "MARK_FIELD"),
                    named.map { it.role to it.name },
                )
                assertEquals("$detail", "500", named.last().value)
                assertFalse("$detail: $snapshot", "MARK_TITLE" in snapshot.toString())
            }
        }
}
