package dev.phoneagent.app.helper

import android.content.ComponentName
import android.os.Binder
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.privileged.handoff.HandoffBundles
import dev.phoneagent.privileged.handoff.HandoffContract
import dev.phoneagent.privileged.handoff.HelperOffer
import dev.phoneagent.privileged.handoff.Nonces
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowBinder

@RunWith(AndroidJUnit4::class)
class HelperHandoffProviderTest {
    private val authority = HandoffContract.authority("dev.phoneagent")
    private val provider = Robolectric.setupContentProvider(HelperHandoffProvider::class.java, authority)

    @After
    fun resetCaller() {
        ShadowBinder.reset()
    }

    private fun asCaller(uid: Int) {
        ShadowBinder.setCallingUid(uid)
        ShadowBinder.setCallingPid(FakeHelper.PID)
    }

    @Test
    fun manifestExportsTheProviderBehindAShellOnlyPermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val component = ComponentName(context, HelperHandoffProvider::class.java)

        // Robolectric 4.17 only implements the int-flags overload for providers.
        @Suppress("DEPRECATION")
        val info = context.packageManager.getProviderInfo(component, 0)
        assertEquals(authority, info.authority)
        assertTrue(info.exported)
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", info.readPermission)
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", info.writePermission)
        assertFalse(info.multiprocess)
    }

    @Test
    fun attachFromAnAppIsRejected() {
        asCaller(10_123)
        val extras =
            HandoffBundles.offer(
                Binder(),
                HelperOffer(10_123, FakeHelper.PID, true, Nonces.random(), "/x/base.apk", 1),
            )
        val reply = provider.call(HandoffContract.METHOD_ATTACH, null, extras)
        assertEquals(false, reply?.getBoolean(HandoffContract.KEY_ACCEPTED))
        assertEquals("NOT_SHELL", reply?.getString(HandoffContract.KEY_REASON))
    }

    @Test
    fun armAndStatusAreForTheShell() {
        asCaller(10_123)
        assertEquals(
            false,
            provider
                .call(
                    HandoffContract.METHOD_ARM,
                    Nonces.sha256Hex("x"),
                    null,
                )?.getBoolean(HandoffContract.KEY_ACCEPTED),
        )
        assertNull(provider.call(HandoffContract.METHOD_STATUS, null, null))

        asCaller(HandoffContract.SHELL_UID)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, null)
        val noBoot = provider.call(HandoffContract.METHOD_ARM, Nonces.sha256Hex("x"), null)
        assertEquals(false, noBoot?.getBoolean(HandoffContract.KEY_ACCEPTED))
        assertTrue(noBoot?.getString(HandoffContract.KEY_REASON).orEmpty().contains("BOOT_COUNT"))

        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 2)
        assertEquals(
            true,
            provider
                .call(
                    HandoffContract.METHOD_ARM,
                    Nonces.sha256Hex("x"),
                    null,
                )?.getBoolean(HandoffContract.KEY_ACCEPTED),
        )
        assertNotNull(provider.call(HandoffContract.METHOD_STATUS, null, null)?.getString(HandoffContract.KEY_STATE))
    }

    @Test
    fun unknownMethodsReturnNothing() {
        asCaller(HandoffContract.SHELL_UID)
        assertNull(provider.call("exec", "id", null))
    }
}
