package dev.phoneagent.privileged.cap

import android.content.Intent
import dev.phoneagent.privileged.StartResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Muting, through the Proxy, PrivilegedCore and SystemOps, with `appops` recorded instead of run. */
@RunWith(RobolectricTestRunner::class)
class SystemOpsTest {
    @get:Rule
    val folder = TemporaryFolder()
    private lateinit var helper: CapabilityHarness

    @Before
    fun setUp() {
        helper = CapabilityHarness(stateDir = folder.root)
        helper.commands.answer = { command ->
            if (command.getOrNull(1) == "get") CommandResult(0, "PLAY_AUDIO: allow") else CommandResult(0, "")
        }
        helper.install(MUSIC)
    }

    @After
    fun close() {
        helper.close()
    }

    @Test
    fun onlyAnInstalledJavaPackageNameIsMuted() {
        val refused =
            listOf("10123", "u0a123", "--user", "-a", "com", "1com.example", "com..example", "com.example.gone", "")
        for (name in refused) {
            assertFalse("$name was muted", helper.core.setAudioMuted(name, true))
            assertFalse("$name was unmuted", helper.core.setAudioMuted(name, false))
        }
        assertEquals("appops never ran for a refused name", emptyList<List<String>>(), helper.commands.of("appops"))
    }

    @Test
    fun anInstalledPackageIsMuted() {
        assertTrue(helper.core.setAudioMuted(MUSIC, true))
        assertEquals(
            listOf(
                listOf("appops", "get", "--user", "0", MUSIC, "PLAY_AUDIO"),
                listOf("appops", "set", "--user", "0", MUSIC, "PLAY_AUDIO", "ignore"),
            ),
            helper.commands.of("appops"),
        )
    }

    @Test
    fun unmutingAPackageThisHelperNeverMutedDoesNothing() {
        assertTrue("nothing to undo is not a failure", helper.core.setAudioMuted(MUSIC, false))
        assertEquals("a mode somebody else set is left alone", emptyList<List<String>>(), helper.commands.of("appops"))
    }

    @Test
    fun unmutingRestoresTheEarlierModeOnceAndThenDoesNothing() {
        assertTrue(helper.core.setAudioMuted(MUSIC, true))
        assertTrue(helper.core.setAudioMuted(MUSIC, false))
        assertTrue(helper.core.setAudioMuted(MUSIC, false))
        assertEquals(
            listOf(
                listOf("appops", "get", "--user", "0", MUSIC, "PLAY_AUDIO"),
                listOf("appops", "set", "--user", "0", MUSIC, "PLAY_AUDIO", "ignore"),
                listOf("appops", "set", "--user", "0", MUSIC, "PLAY_AUDIO", "allow"),
            ),
            helper.commands.of("appops"),
        )
    }

    @Test
    fun aColdStartNeverForceStopsAnInvalidPackageName() {
        // The package manager never hands out such a name; the force-stop target is checked anyway.
        val odd = helper.install("--user", activity = "--user.Main")
        val display = helper.display()
        assertEquals(StartResult.REFUSED, helper.core.startActivity(display, Intent().setComponent(odd), 0, true))
        assertEquals("no command ran", emptyList<List<String>>(), helper.commands.commands)
        assertEquals(emptyList<Pair<Intent, Int>>(), helper.bridge.atm.started)
    }

    private companion object {
        const val MUSIC = "com.example.music"
    }
}
