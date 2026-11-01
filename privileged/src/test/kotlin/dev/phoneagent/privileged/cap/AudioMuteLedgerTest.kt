package dev.phoneagent.privileged.cap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AudioMuteLedgerTest {
    private class FakeStore(
        var text: String = "",
        val failWrites: Boolean = false,
    ) : MuteStore {
        var writes = 0

        override fun read(): String = text

        override fun write(text: String) {
            writes++
            if (failWrites) throw IOException("read-only")
            this.text = text
        }
    }

    @Test
    fun aMutedPackageIsRememberedWithItsEarlierMode() {
        val store = FakeStore()
        val ledger = AudioMuteLedger(store)
        ledger.put("com.example.shop", "allow")
        assertTrue("com.example.shop" in ledger)
        assertEquals("allow", ledger["com.example.shop"])
        assertEquals("com.example.shop allow\n", store.text)
    }

    @Test
    fun theLedgerSurvivesARestart() {
        val store = FakeStore("com.example.shop ignore\ncom.example.radio default\n")
        val ledger = AudioMuteLedger(store)
        assertEquals(listOf("com.example.shop", "com.example.radio"), ledger.packages())
        assertEquals("ignore", ledger["com.example.shop"])
    }

    @Test
    fun aRestoredPackageIsForgottenAndTheFileShrinks() {
        val store = FakeStore()
        val ledger = AudioMuteLedger(store)
        ledger.put("a.b", "allow")
        ledger.put("c.d", "ignore")
        val written = mutableListOf<Pair<String, String>>()
        assertTrue(
            ledger.restoreAll { pkg, mode ->
                written += pkg to mode
                true
            },
        )
        assertEquals(listOf("a.b" to "allow", "c.d" to "ignore"), written)
        assertEquals(emptyList<String>(), ledger.packages())
        assertEquals("", store.text)
    }

    @Test
    fun aPackageThatCouldNotBeRestoredStaysInTheLedger() {
        val ledger = AudioMuteLedger(FakeStore())
        ledger.put("a.b", "allow")
        ledger.put("c.d", "allow")
        assertFalse(ledger.restoreAll { pkg, _ -> pkg != "c.d" })
        assertEquals(listOf("c.d"), ledger.packages())
    }

    @Test
    fun removingAPackageThatIsNotThereWritesNothing() {
        val store = FakeStore()
        val ledger = AudioMuteLedger(store)
        ledger.remove("a.b")
        assertEquals(0, store.writes)
    }

    @Test
    fun aLedgerWithoutAStoreStillWorks() {
        val ledger = AudioMuteLedger(null)
        ledger.put("a.b", "ignore")
        assertEquals("ignore", ledger["a.b"])
    }

    @Test
    fun aStoreThatCannotWriteIsTheStoresProblem() {
        // MuteFile swallows and logs its own IO errors; the ledger lets them through.
        val ledger = AudioMuteLedger(FakeStore(failWrites = true))
        assertThrows(IOException::class.java) { ledger.put("a.b", "ignore") }
        assertEquals("the in-memory ledger still mutes", "ignore", ledger["a.b"])
    }

    @Test
    fun aPackageWithNoEarlierOpIsRestoredToAllow() {
        // An explicit "default" keeps the app silent; the system's own default is allow.
        assertEquals("allow", AudioMuteLedger.restoreMode(AudioMuteLedger.DEFAULT))
        assertEquals("allow", AudioMuteLedger.restoreMode(null))
        assertEquals("allow", AudioMuteLedger.restoreMode(""))
        assertEquals("ignore", AudioMuteLedger.restoreMode("ignore"))
        assertEquals("deny", AudioMuteLedger.restoreMode("deny"))
    }

    @Test
    fun theAppOpsOutputIsParsed() {
        assertEquals("ignore", AudioMuteLedger.parseMode("PLAY_AUDIO: ignore; time=+1h2m3s ago"))
        assertEquals("allow", AudioMuteLedger.parseMode("PLAY_AUDIO: allow"))
        assertEquals(AudioMuteLedger.DEFAULT, AudioMuteLedger.parseMode("No operations."))
        assertEquals(AudioMuteLedger.DEFAULT, AudioMuteLedger.parseMode(""))
    }

    @Test
    fun malformedLinesAreIgnored() {
        val parsed = AudioMuteLedger.parse("a.b allow\ngarbage\n\nc.d ignore\nx.y NOT_A_MODE\n")
        assertEquals(mapOf("a.b" to "allow", "c.d" to "ignore"), parsed)
    }

    @Test
    fun formatAndParseRoundTrip() {
        val modes = mapOf("a.b" to "allow", "c.d" to "ignore")
        assertEquals(modes, AudioMuteLedger.parse(AudioMuteLedger.format(modes)))
    }
}

class GrantSpecTest {
    @Test
    fun onlyWriteSecureSettingsMayBeGranted() {
        assertTrue(GrantSpec.isAllowed(GrantSpec.WRITE_SECURE_SETTINGS))
    }

    @Test
    fun everythingElseIsRefused() {
        listOf(
            null,
            "",
            "android.permission.WRITE_SETTINGS",
            "android.permission.INSTALL_PACKAGES",
            "android.permission.READ_SMS",
            "WRITE_SECURE_SETTINGS",
        ).forEach { assertFalse("$it was allowed", GrantSpec.isAllowed(it)) }
    }
}
