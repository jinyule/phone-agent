package dev.phoneagent.core.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class SnapshotModeTest {
    @Test
    fun `no property means replay`() {
        assertEquals(SnapshotMode.REPLAY, SnapshotMode.parse(null))
    }

    @Test
    fun `each mode is read by its name`() {
        assertEquals(SnapshotMode.entries, SnapshotMode.entries.map { SnapshotMode.parse(it.wire) })
    }

    @Test
    fun `another value fails and lists the modes`() {
        val failure = assertThrows<IllegalArgumentException> { SnapshotMode.parse("Replay") }
        assertTrue("replay, record, refresh" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `an empty value fails`() {
        assertThrows<IllegalArgumentException> { SnapshotMode.parse("") }
    }

    /** Without the `phoneagent.session-replay` convention on `:core`, every mode would silently replay. */
    @Test
    fun `under Gradle the test JVM gets a mode and core's fixture directory`() {
        assumeTrue(System.getProperty(GRADLE_WORKER) != null, "not run by Gradle")
        assertNotNull(System.getProperty(SnapshotMode.PROPERTY), SnapshotMode.PROPERTY)
        assertEquals(Path.of("src/test/resources/sessions").toAbsolutePath(), SnapshotMode.fixtures)
    }

    @Test
    fun `the current mode is the one the property names`() {
        val passed = System.getProperty(SnapshotMode.PROPERTY)
        assertEquals(
            passed?.let { value ->
                SnapshotMode.entries.single { it.wire == value }
            } ?: SnapshotMode.REPLAY,
            SnapshotMode.current(),
        )
    }

    private companion object {
        /** Set by Gradle in every test worker JVM. */
        const val GRADLE_WORKER = "org.gradle.test.worker"
    }
}
