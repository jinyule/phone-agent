package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.Direction
import dev.phoneagent.core.ports.Target
import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.tool.ScreenPresenter

/** `phone_scroll(screen, direction, ref?)` (phase1 §5): scroll one page. */
internal class PhoneScrollTool(
    presenter: ScreenPresenter,
    timeoutMs: Long,
) : ScreenActionTool(
        name = "phone_scroll",
        description =
            "滚动一页。direction=down 看下面的内容，up 看上面的，left、right 看左右的。" +
                "ref 指定要滚动的列表（屏幕里用 ↕ 或 ↔ 标出的）；不填就滚动主列表。",
        presenter = presenter,
        timeoutMs = timeoutMs,
        declare = {
            string("screen", "这次滚动依据的那一屏的编号，如 s12。", required = true)
            string("direction", "想看哪个方向的内容。", required = true, enum = DIRECTIONS.keys.toList())
            string("ref", "要滚动的列表编号，如 @e5。不填就滚动主列表。")
        },
    ) {
    override val usage: String = "phone_scroll(screen, direction=up/down/left/right, ref?)"

    override fun plan(args: ArgReader): ScreenPlan? {
        val screen = args.snapshot("screen")
        val direction = args.choice("direction", DIRECTIONS, required = true)
        val ref = args.ref("ref")
        // An invalid `ref` is already a recorded problem, so `finish` rejects the call.
        return if (screen == null || direction == null) {
            null
        } else {
            val target = ref?.let(Target::Ref)
            val done = "已" + WORDS.getValue(direction) + "滚动" + target?.let { " " + it.shown() }.orEmpty()
            ScreenPlan(UiAction.Scroll(target, direction), screen, done)
        }
    }
}

private val DIRECTIONS =
    linkedMapOf(
        "up" to Direction.UP,
        "down" to Direction.DOWN,
        "left" to Direction.LEFT,
        "right" to Direction.RIGHT,
    )

private val WORDS =
    mapOf(Direction.UP to "向上", Direction.DOWN to "向下", Direction.LEFT to "向左", Direction.RIGHT to "向右")
