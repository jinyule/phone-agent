package dev.phoneagent.core.tools

import dev.phoneagent.core.ports.UiAction
import dev.phoneagent.core.tool.ScreenPresenter

/** `phone_tap(screen, ref | x+y, long?)` (phase1 §5): tap or long-press one target. */
internal class PhoneTapTool(
    presenter: ScreenPresenter,
    timeoutMs: Long,
) : ScreenActionTool(
        name = "phone_tap",
        description =
            "点击一个元素。优先用 ref 指定最近一屏里的元素编号（如 @e10）；没有编号时用 x、y 坐标（0–999）。" +
                "screen 填这个元素所在那一屏的编号（如 s12）。long=true 是长按。返回点击后的画面变化。",
        presenter = presenter,
        timeoutMs = timeoutMs,
        declare = {
            string("screen", "这次点击依据的那一屏的编号，如 s12。", required = true)
            string("ref", "要点的元素编号，写 @e10 或 e10。和 x、y 二选一。")
            integer("x", "横坐标，0 在最左，999 在最右。和 ref 二选一，要和 y 一起给。", COORDINATES)
            integer("y", "纵坐标，0 在最上，999 在最下。", COORDINATES)
            boolean("long", "true 表示长按。默认 false。")
        },
    ) {
    override val usage: String = "phone_tap(screen, ref 或 x+y, long?)"

    override fun plan(args: ArgReader): ScreenPlan? {
        val screen = args.snapshot("screen")
        val target = args.target()
        val long = args.boolean("long") ?: false
        return if (screen == null || target == null) {
            null
        } else {
            ScreenPlan(UiAction.Tap(target, long), screen, (if (long) "已长按 " else "已点击 ") + target.shown())
        }
    }
}
