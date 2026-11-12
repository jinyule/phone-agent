package dev.phoneagent.privileged.cap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric: a restore that throws is logged.
@RunWith(RobolectricTestRunner::class)
class AudioMuteLedgerTest {
    private class FakeStore(
        var text: String = "",
        val failWrites: Boolean = false,
    ) : MuteStore {
        var writes = 0

        override fun read(): String = text

        override fun write(text: String): Boolean {
            writes++
            if (failWrites) return false
            this.text = text
            return true
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
    fun aRestoreThatThrowsDoesNotStopTheOthersAndStaysInTheLedger() {
        val ledger = AudioMuteLedger(FakeStore())
        ledger.put("a.b", "allow")
        ledger.put("c.d", "ignore")
        ledger.put("e.f", "default")
        val written = mutableListOf<String>()
        val empty =
            ledger.restoreAll { pkg, mode ->
                check(pkg != "a.b") { "appops could not start" }
                written += "$pkg $mode"
                true
            }
        assertFalse(empty)
        assertEquals("the failure does not stop the others", listOf("c.d ignore", "e.f allow"), written)
        assertEquals("kept for the next releaseAll", listOf("a.b"), ledger.packages())
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
    fun aMuteThatCannotBeSavedIsNotRecorded() {
        val ledger = AudioMuteLedger(FakeStore(failWrites = true))
        assertFalse(ledger.put("a.b", "ignore"))
        assertFalse("nothing is recorded that a restarted helper would not know", "a.b" in ledger)
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

    @Test
    fun aLedgerLineOf64KiBIsReadOrDroppedWithoutOverflowingTheStack() {
        // FWD-B1 sweep: PackageNames checked the shape with a regex repeating a group, which overflowed the stack.
        val valid = "a.".repeat(KIB_64 / 2 - 1) + "aa"
        val malformed = "a.".repeat(KIB_64 / 2)
        val observed =
            mapOf("valid" to valid, "ends in ." to malformed).mapValues { (_, pkg) ->
                try {
                    AudioMuteLedger
                        .parse("$pkg allow\n")
                        .keys
                        .map { it.length }
                        .toString()
                } catch (overflow: StackOverflowError) {
                    overflow.toString()
                }
            }
        assertEquals(mapOf("valid" to "[$KIB_64]", "ends in ." to "[]"), observed)
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

/** 64 KiB, in characters (the names are ASCII). */
private const val KIB_64 = 65_536
