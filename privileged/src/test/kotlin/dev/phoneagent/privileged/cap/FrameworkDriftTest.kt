package dev.phoneagent.privileged.cap

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import dev.phoneagent.privileged.DisplayResult
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A hidden framework API that changed shape (a field gone, a field of another class, an answer of
 * another type) is a capability that is unavailable: the call reports its failure value, and
 * nothing unplanned reaches the Binder boundary.
 */
@RunWith(RobolectricTestRunner::class)
class FrameworkDriftTest {
    @get:Rule
    val folder = TemporaryFolder()
    private val helper by lazy { CapabilityHarness(stateDir = folder.root) }

    @After
    fun close() {
        helper.close()
        assertEquals("nothing reached the native boundary", emptyList<RuntimeException>(), helper.wire.uncaught)
    }

    private fun settingsOnMain(): ComponentName {
        val settings =
            helper.install(
                "com.android.settings",
                filter = CapabilityHarness.filter(Settings.ACTION_SETTINGS),
            )
        helper.bridge.atm.tasks += CapabilityHarness.task(1, TaskRow.MAIN_DISPLAY, settings, visible = false)
        return settings
    }

    @Test
    fun aTaskListWithoutARequiredFieldIsUnavailable() {
        settingsOnMain()
        val display = helper.display()
        helper.bridge.taskInfoClass = Any::class.java
        assertEquals(-1, helper.core.topTask(TaskRow.MAIN_DISPLAY))
        assertFalse(helper.core.moveTask(1, display))
        assertEquals(StartResult.ERROR, helper.core.startActivity(display, Intent(Settings.ACTION_SETTINGS), 0, false))
        assertEquals("nothing was started", emptyList<Pair<Intent, Int>>(), helper.bridge.atm.started)
    }

    @Test
    fun taskFieldsThatDoNotReadTheReportedTasksAreUnavailable() {
        settingsOnMain()
        val display = helper.display()
        helper.bridge.taskInfoClass = ForeignTaskInfo::class.java
        assertEquals(-1, helper.core.topTask(TaskRow.MAIN_DISPLAY))
        assertEquals(StartResult.ERROR, helper.core.startActivity(display, Intent(Settings.ACTION_SETTINGS), 0, false))
    }

    @Test
    fun aStartAnswerThatIsNotANumberIsAnErrorAndIsNotRetried() {
        helper.install("com.android.settings", filter = CapabilityHarness.filter(Settings.ACTION_SETTINGS))
        val display = helper.display()
        helper.bridge.atm.startResult = "started"
        assertEquals(StartResult.ERROR, helper.core.startActivity(display, Intent(Settings.ACTION_SETTINGS), 0, false))
        assertEquals("asked once", 1, helper.bridge.atm.started.size)
        assertEquals("no second start through am", emptyList<List<String>>(), helper.commands.of("am"))
    }

    @Test
    fun anImePolicyThatReadsBackAsSomethingElseFailsTheDisplay() {
        helper.bridge.wm.imeReadBack = "local"
        assertEquals(DisplayResult.FAILED, helper.display())
    }

    @Test
    fun anInjectionAnswerThatIsNotABooleanIsAFailedInjection() {
        val display = helper.display()
        assertTrue("display created: $display", display >= 0)
        helper.bridge.input.answer = "injected"
        assertFalse(helper.core.injectKey(display, android.view.KeyEvent.KEYCODE_BACK))
        assertEquals(1, helper.bridge.input.injected)
    }

    /** A class with the task fields' names that is not the class of the tasks `getTasks` reports. */
    @Suppress("unused") // Read through reflection only.
    class ForeignTaskInfo {
        @JvmField var taskId = 0

        @JvmField var displayId = 0

        @JvmField var topActivity: ComponentName? = null

        @JvmField var baseActivity: ComponentName? = null
    }
}
