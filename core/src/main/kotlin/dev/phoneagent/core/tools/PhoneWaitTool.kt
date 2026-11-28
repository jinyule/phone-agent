package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `phone_wait(condition, text?, timeoutMs?)` (phase1 §5): waits until a text appears or
 * disappears, or until the screen is quiet for [stableQuietMs]. Ends with the page as it is then;
 * an unmet condition is an error result so the model notices. All waiting is coroutine time
 * (`delay`, `withTimeoutOrNull`), so it is cancellable and testable in virtual time.
 *
 * - **appear / disappear**: read, check, and if not yet, read again one [readIntervalMs] later,
 *   until the deadline. Reads, not change events, decide: [ScreenPort.awaitChange] only reports
 *   changes after it is called, so a change between a read and the call would be lost, while the
 *   next read always sees it; and a screen that changes every frame still costs one read per
 *   interval. At the deadline the last page is checked once more, so a condition that came true
 *   in the meantime is reported as met.
 *
 *   The wait between reads is not raced against [ScreenPort.awaitChange] ("whichever comes
 *   first"): the minimum read interval is also the longest wait, so a change event could never
 *   bring a read forward; racing would only add one port call per round, with the same result.
 * - **stable**: [ScreenPort.awaitChange] until a quiet period of [stableQuietMs]; each look after
 *   a change takes at least [readIntervalMs], so a port that reports changes without suspending
 *   cannot spin the loop or hold off cancellation.
 */
internal class PhoneWaitTool(
    private val presenter: ScreenPresenter,
    private val stableQuietMs: Long,
    private val readIntervalMs: Long,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "phone_wait",
        description =
            "等屏幕满足条件再返回：condition=appear 等 text 出现，disappear 等 text 消失，stable 等画面不再变化（加载、动画）。" +
                "timeoutMs 最多 30000，默认 10000。返回等完之后的一屏。",
        mask = presenter::mask,
        declare = {
            string("condition", "等什么。", required = true, enum = CONDITIONS.keys.toList())
            string("text", "appear、disappear 时要等的文字（按包含匹配）。stable 时不填。")
            integer("timeoutMs", "最多等多久（毫秒）。默认 10000。", MIN_WAIT_MS..MAX_WAIT_MS)
        },
    ) {
    override val group: ToolGroup = ToolGroup.PHONE
    override val risk: RiskClass = RiskClass.READ
    override val concurrencySafe: Boolean = true

    override suspend fun execute(
        call: ToolCall,
        ctx: ToolContext,
    ): ToolResult {
        val args = reader(call)
        val condition = args.choice("condition", CONDITIONS, required = true)
        val text = args.string("text", max = MAX_TEXT)
        val timeout = args.integer("timeoutMs", MIN_WAIT_MS..MAX_WAIT_MS) ?: DEFAULT_WAIT_MS
        when {
            condition == Condition.STABLE && args.has("text") -> {
                args.problem("condition=stable 不用 `text`")
            }

            condition != null && condition != Condition.STABLE && !args.has("text") -> {
                args.problem("condition=${condition.wire} 需要 `text`")
            }
        }
        val plan = condition?.let { Wait(it, text?.let(::normalize), text, timeout) }
        return when (val parsed = args.finish(USAGE, plan)) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> run(ctx, parsed.value)
        }
    }

    private suspend fun run(
        ctx: ToolContext,
        wait: Wait,
    ): ToolResult {
        val screen = ctx.screen ?: return ToolResults.NO_SCREEN
        return when (val waited = screenCall { waitFor(screen, wait) }) {
            is ScreenCall.Unavailable -> ToolResults.unavailable(waited.error, presenter)
            is ScreenCall.Done -> report(wait, waited.value)
        }
    }

    private suspend fun waitFor(
        screen: ScreenPort,
        wait: Wait,
    ): Pair<Boolean, ScreenSnapshot> {
        val met = withTimeoutOrNull(wait.timeoutMs) { untilMet(screen, wait) }
        if (met != null) return true to met
        val last = screen.snapshot()
        return (wait.condition != Condition.STABLE && matches(last, wait)) to last
    }

    private suspend fun untilMet(
        screen: ScreenPort,
        wait: Wait,
    ): ScreenSnapshot {
        if (wait.condition == Condition.STABLE) {
            do {
                val changed = changedWithin(screen)
            } while (changed)
            return screen.snapshot()
        }
        var snapshot = screen.snapshot()
        while (!matches(snapshot, wait)) {
            delay(readIntervalMs)
            snapshot = screen.snapshot()
        }
        return snapshot
    }

    /** Whether the screen changed within [stableQuietMs]; after a change, returns no sooner than [readIntervalMs]. */
    private suspend fun changedWithin(screen: ScreenPort): Boolean =
        coroutineScope {
            val floor = launch { delay(readIntervalMs) }
            val changed = screen.awaitChange(stableQuietMs)
            if (changed) floor.join() else floor.cancel()
            changed
        }

    private fun matches(
        snapshot: ScreenSnapshot,
        wait: Wait,
    ): Boolean = shows(snapshot, wait.needle) == (wait.condition == Condition.APPEAR)

    private fun report(
        wait: Wait,
        result: Pair<Boolean, ScreenSnapshot>,
    ): ToolResult {
        val (met, snapshot) = result
        val shown = wait.text?.let { "「${presenter.mask(it)}」" }.orEmpty()
        val seconds = seconds(wait.timeoutMs)
        val line =
            when (wait.condition) {
                Condition.APPEAR -> if (met) "等到了：${shown}出现了。" else "等了 $seconds 秒，${shown}还没有出现。"
                Condition.DISAPPEAR -> if (met) "等到了：${shown}消失了。" else "等了 $seconds 秒，${shown}还在。"
                Condition.STABLE -> if (met) "画面已经稳定。" else "等了 $seconds 秒，画面一直在变。"
            }
        val details =
            buildJsonObject {
                put("met", met)
                put("screen", snapshot.id.value)
            }
        val text = line + "\n" + presenter.page(snapshot, SnapshotDetail.BRIEF)
        return ToolResult(if (met) ToolStatus.OK else ToolStatus.ERROR, listOf(Part.Text(text)), details)
    }

    /** [ms] as seconds with at most one decimal, e.g. `2`, `0.5`. */
    private fun seconds(ms: Long): String {
        val tenths = ms / TENTH_MS
        return if (tenths % DECIMAL == 0L) "${tenths / DECIMAL}" else "${tenths / DECIMAL}.${tenths % DECIMAL}"
    }

    private fun shows(
        snapshot: ScreenSnapshot,
        needle: String?,
    ): Boolean {
        val texts =
            snapshot.elements.flatMap { listOfNotNull(it.name, it.value, it.hint) } +
                snapshot.ocr.orEmpty().map { it.text }
        return needle != null && texts.any { normalize(it).contains(needle) }
    }

    private fun normalize(text: String): String = text.filterNot(Char::isWhitespace).lowercase()

    private enum class Condition(
        val wire: String,
    ) {
        APPEAR("appear"),
        DISAPPEAR("disappear"),
        STABLE("stable"),
    }

    /** A validated wait; [needle] is [text] normalized for matching. */
    private data class Wait(
        val condition: Condition,
        val needle: String?,
        val text: String?,
        val timeoutMs: Long,
    )

    companion object {
        /** Longest wait the schema allows (phase1 §5: `timeoutMs ≤ 30000`). */
        const val MAX_WAIT_MS: Long = 30_000
        private const val MIN_WAIT_MS = 500L
        private const val DEFAULT_WAIT_MS = 10_000L
        private const val TENTH_MS = 100L
        private const val DECIMAL = 10L
        private const val MAX_TEXT = 200
        private const val USAGE = "phone_wait(condition=appear/disappear/stable, text?, timeoutMs?)"
        private val CONDITIONS = Condition.entries.associateBy { it.wire }
    }
}
