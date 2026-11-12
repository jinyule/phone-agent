package dev.phoneagent.buildlogic

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** `phoneagent.session-replay` on a bare Java project: what the test task gets, and what fails at configuration. */
class SessionReplayConventionPluginTest {
    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun createProject() {
        File(dir, "settings.gradle").writeText("rootProject.name = 'replay-check'\n")
        File(dir, "build.gradle").writeText(
            """
            plugins {
                id 'java'
                id 'phoneagent.session-replay'
            }
            tasks.register('showTestArguments') {
                def test = tasks.named('test')
                doLast {
                    def t = test.get()
                    println("ARGS " + t.allJvmArgs.findAll { it.startsWith('-Dphoneagent.') })
                    println("INCLUDES " + t.filter.includePatterns)
                }
            }
            """.trimIndent(),
        )
    }

    /** A run with `CI` set to [ci] (`null`: unset), whatever the environment running these tests says. */
    private fun runner(
        ci: String?,
        vararg args: String,
    ): GradleRunner {
        val environment = System.getenv() - "CI" + listOfNotNull(ci?.let { "CI" to it })
        return GradleRunner
            .create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withEnvironment(environment)
            .withArguments(listOf("showTestArguments", "--stacktrace") + args)
    }

    @Test
    fun theTestTaskGetsReplayAndTheFixtureDirectoryByDefault() {
        val output = runner(ci = null).build().output
        val fixtures = File(dir.canonicalFile, "src/test/resources/sessions").path
        assertTrue("-Dphoneagent.sessionSnapshot=replay" in output, output)
        assertTrue("-Dphoneagent.sessionFixtures=$fixtures" in output, output)
        assertTrue("INCLUDES []" in output, output)
    }

    @Test
    fun refreshRunsOnlyTheReplayTests() {
        val output = runner(ci = null, "-PsessionSnapshot=refresh").build().output
        assertTrue("-Dphoneagent.sessionSnapshot=refresh" in output, output)
        assertTrue("INCLUDES [dev.phoneagent.core.replay.*]" in output, output)
    }

    @Test
    fun anUnknownModeFailsAtConfiguration() {
        val output = runner(ci = null, "-PsessionSnapshot=replayy").buildAndFail().output
        assertTrue("-PsessionSnapshot=replayy: expected one of replay, record, refresh" in output, output)
    }

    @Test
    fun recordOnCiFailsAtConfiguration() {
        val output = runner(ci = "true", "-PsessionSnapshot=record").buildAndFail().output
        assertTrue("on CI: CI only replays" in output, output)
    }

    @Test
    fun replayOnCiPasses() {
        val output = runner(ci = "true", "-PsessionSnapshot=replay").build().output
        assertTrue("-Dphoneagent.sessionSnapshot=replay" in output, output)
    }
}
