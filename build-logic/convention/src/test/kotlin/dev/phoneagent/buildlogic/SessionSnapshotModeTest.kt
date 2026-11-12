package dev.phoneagent.buildlogic

import org.gradle.api.InvalidUserDataException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionSnapshotModeTest {
    @Test
    fun noValueIsReplay() {
        assertEquals(SessionSnapshotMode.REPLAY, SessionSnapshotMode.parse(null, ci = false))
    }

    @Test
    fun eachModeIsReadByItsName() {
        assertEquals(
            SessionSnapshotMode.entries,
            SessionSnapshotMode.entries.map {
                SessionSnapshotMode.parse(it.wire, ci = false)
            },
        )
    }

    @Test
    fun anUnknownValueFailsAndListsTheModes() {
        val failure = assertThrows<InvalidUserDataException> { SessionSnapshotMode.parse("replay ", ci = false) }
        assertTrue("expected one of replay, record, refresh" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun recordOnCiFails() {
        assertThrows<InvalidUserDataException> { SessionSnapshotMode.parse("record", ci = true) }
    }

    @Test
    fun refreshOnCiFails() {
        assertThrows<InvalidUserDataException> { SessionSnapshotMode.parse("refresh", ci = true) }
    }

    @Test
    fun replayOnCiPasses() {
        assertEquals(SessionSnapshotMode.REPLAY, SessionSnapshotMode.parse("replay", ci = true))
    }

    @Test
    fun noValueOnCiPasses() {
        assertEquals(SessionSnapshotMode.REPLAY, SessionSnapshotMode.parse(null, ci = true))
    }

    @Test
    fun onlyTheWritingModesWrite() {
        assertEquals(listOf(false, true, true), SessionSnapshotMode.entries.map { it.writes })
    }

    @Test
    fun ciIsTheVariableSetToTrue() {
        assertTrue(SessionSnapshotMode.isCi("true"))
        assertTrue(SessionSnapshotMode.isCi("TRUE"))
    }

    @Test
    fun anUnsetOrOtherCiVariableIsNoCi() {
        assertFalse(SessionSnapshotMode.isCi(null))
        assertFalse(SessionSnapshotMode.isCi("false"))
    }
}
