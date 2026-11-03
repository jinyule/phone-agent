package dev.phoneagent.app.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.DEBUG_CLASSES
import dev.phoneagent.app.DEBUG_MODEL_AUTHORITY
import dev.phoneagent.app.declaredComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The queries of `ReleaseHasNoDebugEntryTest` do find the debug entries in debug builds. */
@RunWith(AndroidJUnit4::class)
class DebugEntryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theDebugManifestDeclaresTheModelProviderForTheShellOnly() {
        assertTrue(DebugModelConfigProvider::class.java.name in declaredComponents(context))

        @Suppress("DEPRECATION")
        val info = checkNotNull(context.packageManager.resolveContentProvider(DEBUG_MODEL_AUTHORITY, 0))
        assertEquals(DebugModelConfigProvider::class.java.name, info.name)
        assertTrue(info.exported)
        assertEquals("android.permission.DUMP", info.readPermission)
        assertEquals("android.permission.DUMP", info.writePermission)
    }

    @Test
    fun theDebugClassesExist() {
        for (name in DEBUG_CLASSES) Class.forName(name)
    }
}
