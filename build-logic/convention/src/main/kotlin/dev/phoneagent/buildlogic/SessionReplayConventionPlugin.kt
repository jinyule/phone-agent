package dev.phoneagent.buildlogic

import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import org.gradle.process.CommandLineArgumentProvider

/**
 * How the session replay tests treat the committed fixtures (engineering-baseline §3.2), chosen
 * with `-PsessionSnapshot=<mode>`.
 */
enum class SessionSnapshotMode(
    val wire: String,
) {
    /** The default and the only mode CI runs: compare, never write. */
    REPLAY("replay"),

    /** Run a recording source and write a new fixture. */
    RECORD("record"),

    /** Replay the recorded model replies, device facts and input; rewrite the expected outputs. */
    REFRESH("refresh"),
    ;

    /** Whether this mode writes into `src/` (so the test task must neither be cached nor skipped). */
    val writes: Boolean get() = this != REPLAY

    companion object {
        /** The Gradle property. */
        const val PROPERTY: String = "sessionSnapshot"

        /**
         * The mode named by [value] (`null`: the property is not set, [REPLAY]). An unknown value
         * fails, naming the accepted ones; on CI ([ci]) any mode but [REPLAY] fails, since the
         * others write fixtures. Both fail with [InvalidUserDataException], at configuration.
         */
        fun parse(
            value: String?,
            ci: Boolean,
        ): SessionSnapshotMode {
            val mode =
                if (value == null) {
                    REPLAY
                } else {
                    entries.firstOrNull { it.wire == value }
                        ?: throw InvalidUserDataException(
                            "-P$PROPERTY=$value: expected one of ${entries.joinToString { it.wire }}",
                        )
                }
            if (ci && mode.writes) {
                throw InvalidUserDataException(
                    "-P$PROPERTY=$value on CI: CI only replays; record and refresh write fixtures",
                )
            }
            return mode
        }

        /** Whether the `CI` environment variable says this is a CI run. */
        fun isCi(variable: String?): Boolean = variable.equals("true", ignoreCase = true)
    }
}

/**
 * `:core` only: passes the session snapshot mode ([SessionSnapshotMode]) and the fixture directory
 * to the `test` task as the system properties `phoneagent.sessionSnapshot` and
 * `phoneagent.sessionFixtures`, with the fixtures as inputs. The mode is parsed while the build is
 * configured, so a bad value, or a writing mode on CI, fails before anything runs. In the writing
 * modes the test task runs only the replay tests and is never cached or up to date: it writes
 * `src/test/resources/sessions`, and a cache hit would skip the writes.
 */
class SessionReplayConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit =
        with(target) {
            val mode =
                SessionSnapshotMode.parse(
                    providers.gradleProperty(SessionSnapshotMode.PROPERTY).orNull,
                    SessionSnapshotMode.isCi(providers.environmentVariable("CI").orNull),
                )
            pluginManager.withPlugin("java") {
                val root = layout.projectDirectory.dir(FIXTURES).asFile
                val arguments =
                    objects.newInstance<SessionReplayArguments>().apply {
                        this.mode.set(mode.wire)
                        directory.set(root.absolutePath)
                        fixtures.from(root)
                    }
                tasks.named<Test>("test") {
                    jvmArgumentProviders.add(arguments)
                    if (mode.writes) {
                        filter.includeTestsMatching(REPLAY_TESTS)
                        doNotTrackState("-P${SessionSnapshotMode.PROPERTY}=${mode.wire} writes $FIXTURES")
                    }
                }
            }
        }

    private companion object {
        const val FIXTURES = "src/test/resources/sessions"
        const val REPLAY_TESTS = "dev.phoneagent.core.replay.*"
    }
}

/** The system properties of [SessionReplayConventionPlugin]; the fixtures count by relative path. */
abstract class SessionReplayArguments : CommandLineArgumentProvider {
    /** The mode's [SessionSnapshotMode.wire] name. */
    @get:Input
    abstract val mode: Property<String>

    /** The fixture files, as inputs of the test task. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtures: ConfigurableFileCollection

    /** Where the fixtures are; the path itself is not an input, so the cache key is relocatable. */
    @get:Internal
    abstract val directory: Property<String>

    override fun asArguments(): Iterable<String> =
        listOf("-Dphoneagent.sessionSnapshot=${mode.get()}", "-Dphoneagent.sessionFixtures=${directory.get()}")
}
