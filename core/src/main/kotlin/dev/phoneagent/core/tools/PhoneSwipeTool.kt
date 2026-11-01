package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.tool.ScreenPresenter

/** `phone_swipe(x1, y1, x2, y2, durationMs)` (phase1 §5): a raw swipe between two points. */
internal class PhoneSwipeTool(
    presenter: ScreenPresenter,
    timeoutMs: Long,
) : ScreenActionTool(
        name = "phone_swipe",
        description =
            "从 (x1,y1) 滑到 (x2,y2)，坐标 0–999，用时 durationMs 毫秒。" +
                "只在 phone_scroll 和 phone_tap 做不到时用，比如拖动滑块、翻轮播图、下拉刷新。",
        presenter = presenter,
        timeoutMs = timeoutMs,
        declare = {
            integer("x1", "起点横坐标，0–999。", COORDINATES, required = true)
            integer("y1", "起点纵坐标，0–999。", COORDINATES, required = true)
            integer("x2", "终点横坐标，0–999。", COORDINATES, required = true)
            integer("y2", "终点纵坐标，0–999。", COORDINATES, required = true)
            integer("durationMs", "滑动用时（毫秒）。快速翻页约 300，拖动约 1000。", DURATIONS, required = true)
        },
    ) {
    override val usage: String = "phone_swipe(x1, y1, x2, y2, durationMs)"

    override fun plan(args: ArgReader): ScreenPlan? {
        val coordinates = POINT_ARGS.mapNotNull { args.integer(it, COORDINATES, required = true)?.toInt() }
        val duration = args.integer("durationMs", DURATIONS, required = true)
        if (coordinates.size < POINT_ARGS.size || duration == null) return null
        val (from, to) = coordinates.chunked(2) { (x, y) -> Target.Point(x, y) }
        return ScreenPlan(UiAction.Swipe(from, to, duration), on = null, done = "已从 ${from.shown()} 滑到 ${to.shown()}")
    }
}

private val DURATIONS = 50L..5000L

private val POINT_ARGS = listOf("x1", "y1", "x2", "y2")
