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
        var saves = 0

        override fun load(): NonceSlots = slots

        override fun save(slots: NonceSlots) {
            saves++
            this.slots = slots
        }
    }

    private val store = MemoryStore()
    private var now = 1_000_000L
    private var boot: Int? = 5
    private var counter = 0
    private var armings = 0

    /** Deterministic, well-formed nonces: n(1), n(2), ... */
    private fun n(i: Int): String = i.toString(16).padStart(Nonces.HEX_LENGTH, '0')

    private fun nonces(
        launchTtlMs: Long = 30_000,
        armedTtlMs: Long = 60_000,
    ) = HandoffNonces(store, { now }, { boot }, launchTtlMs, armedTtlMs) { n(++counter) }

    /** A resume nonce of helper [pid], already in the store. */
    private fun resumeOf(pid: Int = PID): String =
        n(RESUME_BASE + pid).also {
            store.slots =
                store.slots.copy(resume = it, resumePid = pid)
        }

    /** Arms a nonce, admits helper [pid] with it and returns the resume nonce [pid] was given. */
    private fun HandoffNonces.admitArmed(pid: Int = PID): String {
        val nonce = n(ARMED_BASE + ++armings)
        assertTrue(arm(Nonces.sha256Hex(nonce)))
        return checkNotNull(admit(nonce, pid))
    }

    @Test
    fun launchNonceMatchesOnlyWithinItsTtl() {
        val gate = nonces(launchTtlMs = 30_000)
        val launch = gate.issueLaunch()
        assertEquals(NonceSlot.LAUNCH, gate.match(launch, PID))
        now += 30_000
        assertEquals(NonceSlot.LAUNCH, gate.match(launch, PID))
        now += 1
        assertNull(gate.match(launch, PID))
    }

    @Test
    fun aClockBelowTheIssueTimeNeverMatches() {
        val gate = nonces()
        val launch = gate.issueLaunch()
        now -= 1
        assertNull(gate.match(launch, PID))
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
        assertNull("launch", afterReboot.match(launch, PID))
        assertNull("armed", afterReboot.match(armed, PID))
        assertNull(afterReboot.consume(launch, PID))
    }

    @Test
    fun anUnknownBootNeverMatchesTimedNonces() {
        boot = null
        val gate = nonces()
        val launch = gate.issueLaunch()
        val armed = n(78)
        assertTrue(gate.arm(Nonces.sha256Hex(armed)))
        assertNull(gate.match(launch, PID))
        assertNull(gate.match(armed, PID))
        assertEquals("the resume nonce is not timed", NonceSlot.RESUME, gate.match(resumeOf(), PID))
    }

    @Test
    fun aResumeNonceIsNotTiedToTheBoot() {
        val resume = resumeOf()
        boot = 6
        assertEquals(NonceSlot.RESUME, nonces().match(resume, PID))
    }

    @Test
    fun armedNonceMatchesThroughItsHashOnly() {
        val gate = nonces(armedTtlMs = 60_000)
        val nonce = n(77)
        val hash = Nonces.sha256Hex(nonce)
        assertTrue(gate.arm(hash))
        assertEquals(NonceSlot.ARMED, gate.match(nonce, PID))
        assertNull("the hash itself is not a nonce", gate.match(hash, PID))
        now += 60_001
        assertNull(gate.match(nonce, PID))
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
    fun resumeNonceNeverExpiresAndRotatesWithOneGenerationOfOverlap() {
        val gate = nonces()
        val first = gate.admitArmed()
        now += 10L * 24 * 60 * 60 * 1000
        assertEquals(NonceSlot.RESUME, gate.match(first, PID))
        val second = checkNotNull(gate.admit(first, PID))
        assertEquals("kept until the next admission", NonceSlot.PREVIOUS_RESUME, gate.match(first, PID))
        assertEquals(NonceSlot.RESUME, gate.match(second, PID))
        val third = checkNotNull(gate.admit(second, PID))
        assertNull("gone once the newer one was used", gate.match(first, PID))
        assertEquals(NonceSlot.PREVIOUS_RESUME, gate.match(second, PID))
        assertEquals(NonceSlot.RESUME, gate.match(third, PID))
    }

    @Test
    fun anAdmissionUsesTheNonceUpAndHandsOutTheNextResumeNonceInOneSave() {
        val gate = nonces()
        val old = resumeOf(PID)
        val armed = n(ARMED_BASE)
        assertTrue(gate.arm(Nonces.sha256Hex(armed)))
        val saves = store.saves

        val next = checkNotNull(gate.admit(armed, OTHER_PID))

        assertEquals("one save", saves + 1, store.saves)
        assertEquals(
            NonceSlots(resume = next, resumePid = OTHER_PID, previousResume = old, previousResumePid = PID),
            store.slots,
        )
        assertNull("the armed nonce is used up", gate.match(armed, OTHER_PID))
    }

    @Test
    fun theReplacedHelperCanComeBackOnceWithItsResumeNonce() {
        val gate = nonces()
        val old = gate.admitArmed(PID)
        val replacing = gate.admitArmed(OTHER_PID)

        assertEquals(NonceSlot.PREVIOUS_RESUME, gate.match(old, PID))
        val back = checkNotNull(gate.admit(old, PID))
        assertEquals("the one presented is kept", NonceSlot.PREVIOUS_RESUME, gate.match(old, PID))
        assertNull("the replacing helper's nonce is gone", gate.match(replacing, OTHER_PID))
        assertEquals(NonceSlot.RESUME, gate.match(back, PID))
    }

    @Test
    fun aResumeNonceMatchesOnlyForTheHelperProcessItWasGivenTo() {
        val gate = nonces()
        val first = gate.admitArmed(PID)
        gate.admitArmed(OTHER_PID)
        val before = store.slots
        assertNull(gate.match(first, OTHER_PID))
        assertNull(gate.admit(first, OTHER_PID))
        assertNull(gate.consume(first, OTHER_PID))
        assertEquals(before, store.slots)
        store.slots = store.slots.copy(resume = "e".repeat(Nonces.HEX_LENGTH), resumePid = null)
        assertNull("no pid recorded: no match", gate.match("e".repeat(Nonces.HEX_LENGTH), PID))
    }

    @Test
    fun aNonceThatIsNotLiveAdmitsNothingAndSavesNothing() {
        val gate = nonces()
        resumeOf()
        val saves = store.saves
        assertNull(gate.admit(n(12345), PID))
        assertNull(gate.admit(null, PID))
        assertEquals(saves, store.saves)
    }

    @Test
    fun consumeClearsOnlyTheMatchedSlot() {
        val gate = nonces()
        val resume = resumeOf()
        val launch = gate.issueLaunch()
        val armed = n(99)
        gate.arm(Nonces.sha256Hex(armed))

        assertEquals(NonceSlot.RESUME, gate.consume(resume, PID))
        assertNull(gate.match(resume, PID))
        assertEquals(NonceSlot.LAUNCH, gate.match(launch, PID))
        assertEquals(NonceSlot.ARMED, gate.match(armed, PID))

        assertEquals(NonceSlot.ARMED, gate.consume(armed, PID))
        assertNull(gate.match(armed, PID))
        assertEquals(NonceSlot.LAUNCH, gate.match(launch, PID))

        assertEquals(NonceSlot.LAUNCH, gate.consume(launch, PID))
        assertEquals(NonceSlots(), store.slots)
    }

    @Test
    fun consumingThePreviousResumeNonceClearsOnlyIt() {
        val gate = nonces()
        val old = gate.admitArmed(PID)
        val current = gate.admitArmed(OTHER_PID)
        assertEquals(NonceSlot.PREVIOUS_RESUME, gate.consume(old, PID))
        assertNull(gate.match(old, PID))
        assertEquals(NonceSlot.RESUME, gate.match(current, OTHER_PID))
    }

    @Test
    fun consumingAnUnknownNonceChangesNothing() {
        val gate = nonces()
        resumeOf()
        val before = store.slots
        assertNull(gate.consume(n(12345), PID))
        assertNull(gate.consume(null, PID))
        assertEquals(before, store.slots)
    }

    @Test
    fun malformedNoncesNeverMatchEvenIfStored() {
        store.slots = NonceSlots(resume = "short", resumePid = PID)
        val gate = nonces()
        assertNull(gate.match("short", PID))
        assertNull(gate.match(null, PID))
    }

    @Test
    fun stateSurvivesANewProcess() {
        val resume = nonces().admitArmed()
        val restarted = HandoffNonces(store, { now }, { boot })
        assertEquals(NonceSlot.RESUME, restarted.match(resume, PID))
    }

    @Test
    fun clearForgetsEverything() {
        val gate = nonces()
        val old = gate.admitArmed(PID)
        val resume = gate.admitArmed(OTHER_PID)
        gate.issueLaunch()
        gate.clear()
        assertNull(gate.match(resume, OTHER_PID))
        assertNull(gate.match(old, PID))
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
        val old = gate.admitArmed(PID)
        val resume = gate.admitArmed(OTHER_PID)
        val launch = gate.issueLaunch()
        val text = store.slots.toString()
        assertFalse(text.contains(old))
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

    private companion object {
        const val PID = 4242
        const val OTHER_PID = 4343
        const val RESUME_BASE = 0x10000
        const val ARMED_BASE = 0x20000
    }
}
