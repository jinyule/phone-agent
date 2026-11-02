package dev.phoneagent.privileged.cap

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The helper starts only what the app's own uid could start, through the Proxy, PrivilegedCore
 * and Launcher: the shell user it runs as holds far more permissions than the app.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchPermissionTest {
    @get:Rule
    val folder = TemporaryFolder()
    private lateinit var helper: CapabilityHarness
    private var display = -1

    @Before
    fun setUp() {
        helper = CapabilityHarness(stateDir = folder.root)
        display = helper.display()
        assertTrue("display created: $display", display >= 0)
    }

    @After
    fun close() {
        helper.close()
    }

    private fun start(intent: Intent): Int = helper.core.startActivity(display, intent, 0, false)

    private fun assertRefused(
        what: String,
        intent: Intent,
        expected: Int = StartResult.REFUSED,
    ) {
        assertEquals(what, expected, start(intent))
        assertEquals("$what: nothing started", emptyList<Pair<Intent, Int>>(), helper.bridge.atm.started)
        assertEquals("$what: no command ran", emptyList<List<String>>(), helper.commands.commands)
    }

    private fun installDialer(): ComponentName =
        helper.install(
            "com.android.server.telecom",
            activity = "com.android.server.telecom.components.UserCallActivity",
            permission = Manifest.permission.CALL_PHONE,
            filter = CapabilityHarness.filter(Intent.ACTION_CALL, scheme = "tel"),
        )

    @Test
    fun aCallIsRefusedWhenTheAppDoesNotHoldCallPhone() {
        installDialer()
        assertRefused("ACTION_CALL tel:", Intent(Intent.ACTION_CALL, Uri.parse("tel:10086")))
    }

    @Test
    fun aCallStartsExactlyTheCheckedActivityOnceTheAppHoldsCallPhone() {
        val dialer = installDialer()
        helper.grantApp(Manifest.permission.CALL_PHONE)
        assertEquals(StartResult.OK, start(Intent(Intent.ACTION_CALL, Uri.parse("tel:10086"))))
        val (started, _) =
            helper.bridge.atm.started
                .single()
        assertEquals("the activity that was checked is the one started", dialer, started.component)
    }

    @Test
    fun anotherAppsUnexportedActivityIsRefused() {
        val hidden = helper.install("com.example.bank", activity = "com.example.bank.Internal", exported = false)
        assertRefused("unexported", Intent().setComponent(hidden))
    }

    @Test
    fun theAppsOwnUnexportedActivityStarts() {
        val own = helper.install(CapabilityHarness.APP, activity = "${CapabilityHarness.APP}.Hidden", exported = false)
        assertEquals(StartResult.OK, start(Intent().setComponent(own)))
    }

    @Test
    fun anIntentWithSeveralHandlersIsRefused() {
        val view = CapabilityHarness.filter(Intent.ACTION_VIEW, scheme = "https")
        helper.install("com.example.browser", filter = view)
        helper.install("com.example.reader", filter = view)
        assertRefused("two handlers", Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")))
    }

    @Test
    fun aComponentThatDoesNotExistIsNotFound() {
        val missing = ComponentName("com.example.gone", "com.example.gone.Main")
        assertRefused("missing component", Intent().setComponent(missing), StartResult.NOT_FOUND)
    }
}
