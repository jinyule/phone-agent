package dev.phoneagent.core.replay

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ActionOutcome
import dev.phoneagent.core.ports.AskResult
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.screen.SensitiveNumbers
import dev.phoneagent.core.session.InboxSpliced
import dev.phoneagent.core.session.JudgmentOutcome
import dev.phoneagent.core.session.QuestionAnswered
import dev.phoneagent.core.session.ScreenActed
import dev.phoneagent.core.session.ScreenFrame
import dev.phoneagent.core.session.ScreenJudged
import dev.phoneagent.core.session.ScreenLaunched
import dev.phoneagent.core.session.ScreenObserved
import dev.phoneagent.core.session.SessionLog
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * gitleaks over a freshly recorded fixture while it is staged under `build/`, before it is
 * installed into the sources (engineering-baseline §3.2, [SessionRecorder.record]). When it is not
 * installed the fixture is not scanned, which the checklist says; CI scans the history.
 */
object Gitleaks {
    private const val TIMEOUT_S = 60L

    /** An ANSI colour code: `--no-color` covers the findings, not gitleaks' own log lines. */
    private val COLOUR = Regex("\u001B\\[[0-9;]*m")

    /**
     * The exit code gitleaks is told to give when it finds leaks: its default, 1, is also what it
     * gives when it fails itself (a config it cannot load, say), and 0 means clean.
     */
    const val LEAKS_EXIT: Int = 42

    /** What a scan found. */
    sealed interface Scan {
        /** gitleaks is not on the PATH. */
        data object NotInstalled : Scan

        /** Nothing. */
        data object Clean : Scan

        /** Leaks, as gitleaks reported them: rule, file and line of each, the secret redacted. */
        data class Found(
            val report: String,
        ) : Scan
    }

    /**
     * Scans [dir] with the repository's config, using [executable] ([onPath] by default; `null` is
     * [Scan.NotInstalled]). Fails with [IllegalStateException] when gitleaks fails itself or does
     * not finish within [timeoutS] seconds: the fixture is then not scanned, which is not the same
     * as leaks.
     */
    fun scan(
        dir: Path,
        executable: Path? = onPath(),
        timeoutS: Long = TIMEOUT_S,
    ): Scan {
        val gitleaks =
            executable
                ?: return Scan.NotInstalled.also { System.err.println("gitleaks not installed: $dir not scanned") }
        val config = Path.of("..", ".gitleaks.toml").takeIf { Files.isRegularFile(it) }
        val command =
            listOf(gitleaks.toString(), "dir", "--verbose", "--redact", "--no-color", "--no-banner") +
                listOf("--exit-code", LEAKS_EXIT.toString()) +
                config?.let { listOf("--config", it.toString()) }.orEmpty() + dir.toString()
        val log = Files.createTempFile("gitleaks", ".log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val finished = process.waitFor(timeoutS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        // gitleaks cuts a finding's context at a byte count: a split character is replaced, not thrown on.
        val report = String(Files.readAllBytes(log), Charsets.UTF_8).replace(COLOUR, "").also { Files.delete(log) }
        check(finished) { "gitleaks did not finish within $timeoutS s: $dir is not scanned" }
        return when (val exit = process.exitValue()) {
            0 -> Scan.Clean
            LEAKS_EXIT -> Scan.Found(report)
            else -> error("gitleaks failed (exit $exit): $dir is not scanned\n$report")
        }
    }

    /** The gitleaks on the PATH, or `null`. */
    fun onPath(): Path? =
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
 * `core/build/session-record/<scenario>/privacy-checklist.md`, never committed: first the screens
 * the runtime withheld from the model (`screen/judged` WITHHELD), whose text is in the fixture all
 * the same; then every screenshot, every other text read from the device, the reasons the device
 * gave for an action or launch it failed or refused (`screen/action`, `screen/launch`), every
 * input and answer of the user, the app list, the app versions and the ROM build
 * (`scenario.json`), and the messages of what the device threw (`replay.override.json`), each
 * with a box to tick once a person saw that it comes from a test account. Texts in which
 * [SensitiveNumbers] masks something are flagged as suspected numbers.
 */
object PrivacyChecklist {
    const val FILE: String = "privacy-checklist.md"

    private const val NOT_SHOWN = "not shown to the model"

    /** The heading of the withheld screens, the first section. */
    const val WITHHELD: String = "Withheld screens: 没给模型看过 (never shown to the model, but in the fixture)"

    /** The heading of the reasons the device gave for an action or launch it failed or refused. */
    const val REASONS: String =
        "Reasons the device gave (screen/action, screen/launch failed or refused; they may quote the screen)"

    /** Writes [text], a checklist ([PrivacyChecklist.text]), as [FILE] into [dir] (created). */
    fun write(
        dir: Path,
        text: String,
    ) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(FILE), text)
    }

    /** The checklist of [log], recorded as [name] with [scenario] and [override], and scanned with [scan]. */
    fun text(
        name: String,
        log: SessionLog,
        scenario: Scenario,
        override: ReplayOverride,
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
            val withheld = withheld(log)
            val (hidden, shown) = screens(log).partition { it.id in withheld }
            section(WITHHELD, hidden.flatMap { screen -> texts(screen).map { withheldItem(screen, withheld, it) } })
            section("Screenshots (blobs)", frames(log))
            section("Text read from the device", shown.flatMap(::texts))
            section(REASONS, reasons(log, withheld))
            section("What the user typed and answered", userTexts(log))
            section("Installed apps (scenario.json)", scenario.device.apps.map { "${it.label} (${it.packageName})" })
            section(
                "App versions (scenario.json device.packages)",
                scenario.device.packages.map { (pkg, version) -> "$pkg $version" },
            )
            section("ROM build (scenario.json device.build)", listOfNotNull(scenario.device.build))
            section("What the device threw (replay.override.json throws.message)", thrown(override))
        }

    /** The rule that withheld each screen judged WITHHELD, by its id. */
    private fun withheld(log: SessionLog): Map<SnapshotId, String?> =
        log.records
            .mapNotNull { it.event as? ScreenJudged }
            .filter { it.outcome == JudgmentOutcome.WITHHELD }
            .associate { it.snapshot to it.rule }

    private fun withheldItem(
        screen: ScreenSnapshot,
        withheld: Map<SnapshotId, String?>,
        text: String,
    ): String = "screen ${screen.id.value} of ${screen.pkg ?: "no app"} (withheld by ${withheld[screen.id]}): $text"

    /**
     * The reason of each action and launch the device failed or refused, which may quote the screen
     * ([ActionOutcome.Status.Failed]): the model was given it only with a screen read back after it
     * and judged shown, so an item says which screen that was, or that the model was not shown it.
     */
    private fun reasons(
        log: SessionLog,
        withheld: Map<SnapshotId, String?>,
    ): List<String> =
        log.records.mapNotNull { record ->
            when (val event = record.event) {
                is ScreenActed -> reason(event.outcome, "screen/action of ${event.callId.value}", withheld)
                is ScreenLaunched -> reason(event.outcome, "screen/launch of ${event.callId.value}", withheld)
                else -> null
            }
        }

    private fun reason(
        outcome: ActionOutcome,
        source: String,
        withheld: Map<SnapshotId, String?>,
    ): String? {
        val (status, reason) =
            when (val status = outcome.status) {
                is ActionOutcome.Status.Failed -> "failed" to status.reason

                is ActionOutcome.Status.Refused -> "refused" to status.reason

                // A stale ref's reason is a fixed hint that quotes nothing.
                ActionOutcome.Status.Ok, is ActionOutcome.Status.StaleRef -> null
            } ?: return null
        val after = outcome.after
        val seen =
            when {
                after == null -> "nothing read back, $NOT_SHOWN"
                after.id in withheld -> "screen ${after.id.value} withheld by ${withheld[after.id]}, $NOT_SHOWN"
                else -> "with screen ${after.id.value}"
            }
        return "$reason ($status, $source; $seen)"
    }

    private fun thrown(override: ReplayOverride): List<String> =
        override.device.mapNotNull { fact ->
            fact.throws?.takeIf { it.message.isNotEmpty() }?.let { thrown ->
                "${thrown.message} (${thrown.type} from ${fact.port} of ${fact.call ?: "no call"}, op ${fact.op})"
            }
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

    /** Every screen read from the device, in log order: observed, and after an action or a launch. */
    private fun screens(log: SessionLog): List<ScreenSnapshot> =
        log.records.mapNotNull { record ->
            when (val event = record.event) {
                is ScreenObserved -> event.snapshot
                is ScreenActed -> event.outcome.after
                is ScreenLaunched -> event.outcome.after
                else -> null
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
