package dev.phoneagent.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads detekt's checkstyle report for the canary sources and fails if any expected rule
 * produced no finding, i.e. the configuration silently stopped enforcing it.
 */
@CacheableTask
abstract class VerifyDetektCanaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val report: RegularFileProperty

    /** One rule id per line; blank lines and `#` comments are ignored. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val expectedRules: RegularFileProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val expected =
            expectedRules
                .get()
                .asFile
                .readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .toSortedSet()
        if (expected.isEmpty()) throw GradleException("expected-rules.txt lists no rules")

        val errors =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(report.get().asFile)
                .getElementsByTagName("error")
        val fired =
            (0 until errors.length)
                .map {
                    errors
                        .item(it)
                        .attributes
                        .getNamedItem("source")
                        .nodeValue
                        .substringAfterLast('.')
                }.toSet()

        val missing = expected - fired
        if (missing.isNotEmpty()) {
            throw GradleException(
                "detekt canary: these rules no longer fire on config/detekt/canary: $missing. " +
                    "Fix config/detekt/detekt.yml (or the canary source) so every listed rule is enforced.",
            )
        }
        marker.get().asFile.writeText(expected.joinToString("\n", postfix = "\n"))
    }
}
