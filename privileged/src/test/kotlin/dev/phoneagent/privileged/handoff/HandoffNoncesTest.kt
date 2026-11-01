package dev.phoneagent.privileged.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffNoncesTest {
    private class MemoryStore : NonceStore {
        var slots = NonceSlots()

        override fun load(): NonceSlots = slots

        override fun save(slots: NonceSlots) {
            this.slots = slots
        }
    }

    private val store = MemoryStore()
    private var now = 1_000_000L
    private var boot: Int? = 5
    private var counter = 0

    /** Deterministic, well-formed nonces: n(1), n(2), ... */
    private fun n(i: Int): String = i.toString(16).padStart(Nonces.HEX_LENGTH, '0')

    private fun nonces(
        launchTtlMs: Long = 30_000,
        armedTtlMs: Long = 60_000,
    ) = HandoffNonces(store, { now }, { boot }, launchTtlMs, armedTtlMs) { n(++counter) }

    @Test
    fun launchNonceMatchesOnlyWithinItsTtl() {
        val gate = nonces(launchTtlMs = 30_000)
        val launch = gate.issueLaunch()
        assertEquals(NonceSlot.LAUNCH, gate.match(launch))
        now += 30_000
        assertEquals(NonceSlot.LAUNCH, gate.match(launch))
        now += 1
        assertNull(gate.match(launch))
    }

    @Test
    fun aClockBelowTheIssueTimeNeverMatches() {
        val gate = nonces()
        val launch = gate.issueLaunch()
        now -= 1
        assertNull(gate.match(launch))
    }

    @Test
    fun aRebootVoidsLaunchAndArmedNoncesEvenWhenTheNewUptimeIsInsideTheirTtl() {
        now = 1_000
        val launch = nonces().issueLaunch()
        val armed = n(77)
        assertTrue(nonces().arm(Nonces.sha256Hex(armed)))
        boot = 6
        now = 1_001
        val afterReboot = nonces()
        assertNull("launch", afterReboot.match(launch))
        assertNull("armed", afterReboot.match(armed))
        assertNull(afterReboot.consume(launch))
    }

    @Test
    fun anUnknownBootNeverMatchesTimedNonces() {
        boot = null
        val gate = nonces()
        val launch = gate.issueLaunch()
        val armed = n(78)
        assertTrue(gate.arm(Nonces.sha256Hex(armed)))
        assertNull(gate.match(launch))
        assertNull(gate.match(armed))
        assertEquals("the resume nonce is not timed", NonceSlot.RESUME, gate.match(gate.issueResume()))
    }

    @Test
    fun aResumeNonceIsNotTiedToTheBoot() {
        val resume = nonces().issueResume()
        boot = 6
        assertEquals(NonceSlot.RESUME, nonces().match(resume))
    }

    @Test
    fun armedNonceMatchesThroughItsHashOnly() {
        val gate = nonces(armedTtlMs = 60_000)
        val nonce = n(77)
        val hash = Nonces.sha256Hex(nonce)
        assertTrue(gate.arm(hash))
        assertEquals(NonceSlot.ARMED, gate.match(nonce))
        assertNull("the hash itself is not a nonce", gate.match(hash))
        now += 60_001
        assertNull(gate.match(nonce))
    }

    @Test
    fun armRejectsMalformedHashesWithoutChangingState() {
        val gate = nonces()
        val good = Nonces.sha256Hex(n(5))
        assertTrue(gate.arm(good))
        val before = store.slots
        assertFalse(gate.arm(null))
        assertFalse(gate.arm(good.uppercase()))
        assertFalse(gate.arm(good.dropLast(1)))
        assertFalse(gate.arm(good + "0"))
        assertFalse(gate.arm(good.replaceRange(0, 1, "g")))
        assertEquals(before, store.slots)
    }

    @Test
    fun resumeNonceNeverExpiresAndRotates() {
        val gate = nonces()
        val first = gate.issueResume()
        now += 10L * 24 * 60 * 60 * 1000
        assertEquals(NonceSlot.RESUME, gate.match(first))
        val second = gate.issueResume()
        assertNull("the previous resume nonce is replaced", gate.match(first))
        assertEquals(NonceSlot.RESUME, gate.match(second))
    }

    @Test
    fun consumeClearsOnlyTheMatchedSlot() {
        val gate = nonces()
        val resume = gate.issueResume()
        val launch = gate.issueLaunch()
        val armed = n(99)
        gate.arm(Nonces.sha256Hex(armed))

        assertEquals(NonceSlot.RESUME, gate.consume(resume))
        assertNull(gate.match(resume))
        assertEquals(NonceSlot.LAUNCH, gate.match(launch))
        assertEquals(NonceSlot.ARMED, gate.match(armed))

        assertEquals(NonceSlot.ARMED, gate.consume(armed))
        assertNull(gate.match(armed))
        assertEquals(NonceSlot.LAUNCH, gate.match(launch))

        assertEquals(NonceSlot.LAUNCH, gate.consume(launch))
        assertEquals(NonceSlots(), store.slots)
    }

    @Test
    fun consumingAnUnknownNonceChangesNothing() {
        val gate = nonces()
        gate.issueResume()
        val before = store.slots
        assertNull(gate.consume(n(12345)))
        assertNull(gate.consume(null))
        assertEquals(before, store.slots)
    }

    @Test
    fun malformedNoncesNeverMatchEvenIfStored() {
        store.slots = NonceSlots(resume = "short")
        val gate = nonces()
        assertNull(gate.match("short"))
        assertNull(gate.match(null))
    }

    @Test
    fun stateSurvivesANewProcess() {
        val resume = nonces().issueResume()
        val restarted = HandoffNonces(store, { now }, { boot })
        assertEquals(NonceSlot.RESUME, restarted.match(resume))
    }

    @Test
    fun clearForgetsEverything() {
        val gate = nonces()
        val resume = gate.issueResume()
        gate.issueLaunch()
        gate.clear()
        assertNull(gate.match(resume))
        assertEquals(NonceSlots(), store.slots)
    }

    @Test
    fun ttlsMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { nonces(launchTtlMs = 0) }
        assertThrows(IllegalArgumentException::class.java) { nonces(armedTtlMs = -1) }
    }

    @Test
    fun toStringNeverShowsNonceValues() {
        val gate = nonces()
        val resume = gate.issueResume()
        val launch = gate.issueLaunch()
        val text = store.slots.toString()
        assertFalse(text.contains(resume))
        assertFalse(text.contains(launch))
    }

    @Test
    fun randomNoncesAreWellFormedAndDistinct() {
        val a = Nonces.random()
        val b = Nonces.random()
        assertTrue(Nonces.isWellFormed(a))
        assertTrue(Nonces.isWellFormed(b))
        assertNotEquals(a, b)
    }

    @Test
    fun sha256HexMatchesTheStandardVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Nonces.sha256Hex("abc"))
    }
}
