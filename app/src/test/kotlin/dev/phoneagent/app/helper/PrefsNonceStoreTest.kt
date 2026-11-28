package dev.phoneagent.app.helper

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.privileged.handoff.NonceSlots
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrefsNonceStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun slotsSurviveANewStoreIncludingTheirBootsAndPids() {
        val slots =
            NonceSlots("r".repeat(64), "l".repeat(64), 1_000, 7, "a".repeat(64), 2_000, 8, 4242, "p".repeat(64), 4141)
        PrefsNonceStore(context).save(slots)
        assertEquals(slots, PrefsNonceStore(context).load())
        val replaced = NonceSlots(resume = "s".repeat(64), resumePid = 4343)
        PrefsNonceStore(context).save(replaced)
        assertEquals("cleared fields stay cleared", replaced, PrefsNonceStore(context).load())
    }

    @Test
    fun anUnknownBootStaysUnknown() {
        val store = PrefsNonceStore(context)
        store.save(NonceSlots(launch = "l".repeat(64), launchAtMs = 1, launchBoot = 3))
        store.save(NonceSlots(launch = "l".repeat(64), launchAtMs = 1, launchBoot = null))
        assertEquals(NonceSlots(launch = "l".repeat(64), launchAtMs = 1), PrefsNonceStore(context).load())
        assertEquals(NonceSlots(), PrefsNonceStore(context).also { it.save(NonceSlots()) }.load())
    }
}
