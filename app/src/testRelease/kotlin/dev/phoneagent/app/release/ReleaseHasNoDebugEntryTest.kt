package dev.phoneagent.app.release

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.DEBUG_CLASSES
import dev.phoneagent.app.DEBUG_MODEL_AUTHORITY
import dev.phoneagent.app.DEBUG_PACKAGE
import dev.phoneagent.app.declaredComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release builds carry no debug-only entry point: neither the model configuration provider (keys
 * from a computer) nor the helper self-check, in the merged manifest or as classes.
 * `DebugEntryTest` checks the same queries find them in debug builds.
 *
 * The release unit tests run this package only (the app convention's filter): tests here check
 * how release differs from debug, everything else runs on debug.
 */
@RunWith(AndroidJUnit4::class)
class ReleaseHasNoDebugEntryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theReleaseManifestDeclaresNoDebugComponent() {
        val names = declaredComponents(context)
        assertTrue(names.toString(), "dev.phoneagent.app.MainActivity" in names)
        assertEquals(emptyList<String>(), names.filter { it.startsWith(DEBUG_PACKAGE) })
        @Suppress("DEPRECATION") // Robolectric 4.17 only implements the int-flags overload.
        val provider = context.packageManager.resolveContentProvider(DEBUG_MODEL_AUTHORITY, 0)
        assertNull(provider)
    }

    @Test
    fun noDebugClassShips() {
        for (name in DEBUG_CLASSES) {
            assertThrows(name, ClassNotFoundException::class.java) { Class.forName(name) }
        }
    }
}
