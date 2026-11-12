package dev.phoneagent.core.replay

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.screen.SensitiveNumbers
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionLog
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * gitleaks over a freshly recorded fixture (engineering-baseline §3.2), as lefthook runs it: when
 * it is not installed the fixture is not scanned, which the checklist says; CI scans the history.
 */
object Gitleaks {
    private const val TIMEOUT_S = 60L
    private const val LEAKS = 1

    /** What a scan found. */
    sealed interface Scan {
        /** gitleaks is not on the PATH. */
        data object NotInstalled : Scan

        /** Nothing. */
        data object Clean : Scan

        /** Leaks, as gitleaks reported them (redacted). */
        data class Found(
            val report: String,
        ) : Scan
    }

    /** Scans [dir] with the repository's config. Fails with [IllegalStateException] when gitleaks itself fails. */
    fun scan(dir: Path): Scan {
        val gitleaks =
            executable()
                ?: return Scan.NotInstalled.also { System.err.println("gitleaks not installed: $dir not scanned") }
        val config = Path.of("..", ".gitleaks.toml").takeIf { Files.isRegularFile(it) }
        val command =
            listOf(gitleaks.toString(), "dir", "--redact", "--no-banner") +
                config?.let { listOf("--config", it.toString()) }.orEmpty() + dir.toString()
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val report = process.inputStream.bufferedReader().readText()
        check(process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) { "gitleaks did not finish within $TIMEOUT_S s" }
        return when (process.exitValue()) {
            0 -> Scan.Clean
            LEAKS -> Scan.Found(report)
            else -> error("gitleaks failed (${process.exitValue()}): $report")
        }
    }

    private fun executable(): Path? =
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { Path.of(it, "gitleaks") }
            .firstOrNull { Files.isExecutable(it) }
}

/**
 * The manual privacy review of a recording (AGENTS §6), written to
 * `core/build/session-record/<scenario>/privacy-checklist.md`, never committed: every screenshot,
 * every text read from the device, every input and answer of the user, and the app list, each with
 * a box to tick once a person saw that it comes from a test account. Texts in which
 * [SensitiveNumbers] masks something are flagged as suspected numbers.
 */
object PrivacyChecklist {
    const val FILE: String = "privacy-checklist.md"

    /** Writes the checklist of [log] (recorded as [name] with [scenario]) and [scan] into [dir]. */
    fun write(
        dir: Path,
        name: String,
        log: SessionLog,
        scenario: Scenario,
        scan: Gitleaks.Scan,
    ) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(FILE), text(name, log, scenario, scan))
    }

    /** The checklist's text. */
    fun text(
        name: String,
        log: SessionLog,
        scenario: Scenario,
        scan: Gitleaks.Scan,
    ): String =
        buildString {
            appendLine("# Privacy review: $name")
            appendLine()
            appendLine(
                "Tick each item once you saw it comes from a test account. Fixtures are committed only after that.",
            )
            appendLine()
            appendLine("gitleaks: ${scanned(scan)}")
            section("Screenshots (blobs)", frames(log))
            section("Text read from the device", deviceTexts(log))
            section("What the user typed and answered", userTexts(log))
            section("Installed apps (scenario.json)", scenario.device.apps.map { "${it.label} (${it.packageName})" })
        }

    private fun scanned(scan: Gitleaks.Scan): String =
        when (scan) {
            Gitleaks.Scan.NotInstalled -> "not scanned (not installed; CI scans the history)"
            Gitleaks.Scan.Clean -> "clean"
            is Gitleaks.Scan.Found -> "FOUND (the recording failed)"
        }

    private fun StringBuilder.section(
        title: String,
        items: List<String>,
    ) {
        appendLine()
        appendLine("## $title")
        appendLine()
        if (items.isEmpty()) appendLine("(none)")
        for (item in items.distinct()) {
            val flag = if (SensitiveNumbers.mask(item) != item) " — suspected number" else ""
            appendLine("- [ ] $item$flag")
        }
    }

    private fun frames(log: SessionLog): List<String> =
        log.records.flatMap { record ->
            when (val event = record.event) {
                is ScreenFrame -> listOf(image(event.blob.value, event.mime, event.width, event.height, "screenshot"))
                is InboxSpliced -> event.added.flatMap { it.parts.filterIsInstance<Part.Image>() }.map(::image)
                else -> emptyList()
            }
        }

    private fun image(part: Part.Image): String =
        image(part.blob.value, part.mime, part.width, part.height, "from the user")

    private fun image(
        blob: String,
        mime: String,
        width: Int,
        height: Int,
        what: String,
    ): String = "blobs/$blob ($what, $mime, $width×$height)"

    private fun deviceTexts(log: SessionLog): List<String> =
        log.records.flatMap { record ->
            when (val event = record.event) {
                is ScreenObserved -> {
                    texts(event.snapshot)
                }

                is ScreenActed -> {
                    event.outcome.after
                        ?.let(::texts)
                        .orEmpty()
                }

                is ScreenLaunched -> {
                    event.outcome.after
                        ?.let(::texts)
                        .orEmpty()
                }

                else -> {
                    emptyList()
                }
            }
        }

    private fun texts(snapshot: ScreenSnapshot): List<String> =
        snapshot.elements.flatMap { listOfNotNull(it.name, it.value, it.hint) } + snapshot.ocr.orEmpty().map { it.text }

    private fun userTexts(log: SessionLog): List<String> =
        log.records.flatMap { record ->
            when (val event = record.event) {
                is InboxSpliced -> {
                    event.added.flatMap { entry ->
                        entry.parts.filterIsInstance<Part.Text>().map { it.text }
                    }
                }

                is QuestionAnswered -> {
                    listOfNotNull((event.result as? AskResult.Answered)?.text)
                }

                else -> {
                    emptyList()
                }
            }
        }
}
