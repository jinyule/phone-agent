package dev.phoneagent.core.replay

import java.nio.file.Path

/**
 * How the session replay tests treat the committed fixtures (engineering-baseline §3.2), chosen with
 * `-PsessionSnapshot=` and passed to the test JVM by the `phoneagent.session-replay` convention as
 * the system property [PROPERTY]:
 * - [REPLAY], the default and the only mode CI runs: compare, never write;
 * - [RECORD]: run a recording source and write a new fixture;
 * - [REFRESH]: replay the fixture's model replies, device facts and user input, and rewrite the
 *   product outputs it expects (no key needed).
 */
enum class SnapshotMode(
    val wire: String,
) {
    REPLAY("replay"),
    RECORD("record"),
    REFRESH("refresh"),
    ;

    companion object {
        /** The system property the Gradle convention sets on the test task. */
        const val PROPERTY: String = "phoneagent.sessionSnapshot"

        /** The system property the Gradle convention sets to the fixture directory. */
        const val FIXTURES_PROPERTY: String = "phoneagent.sessionFixtures"

        /**
         * Where the fixtures are: [FIXTURES_PROPERTY], or outside Gradle `src/test/resources/sessions`
         * relative to `:core` (the test task's working directory).
         */
        val fixtures: Path get() =
            System.getProperty(FIXTURES_PROPERTY)?.let(Path::of)
                ?: Path.of("src/test/resources/sessions")

        /**
         * The mode named by [value]; `null` (a run outside Gradle, e.g. from the IDE) is [REPLAY].
         * Any other value fails loudly with [IllegalArgumentException], naming the accepted ones.
         */
        fun parse(value: String?): SnapshotMode =
            if (value == null) {
                REPLAY
            } else {
                requireNotNull(entries.firstOrNull { it.wire == value }) {
                    "$PROPERTY='$value': expected one of ${entries.joinToString { it.wire }}"
                }
            }

        /** The mode of this test JVM. */
        fun current(): SnapshotMode = parse(System.getProperty(PROPERTY))
    }
}
