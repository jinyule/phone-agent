package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.tool.FramedSnapshot
import dev.phoneagent.core.tool.RiskClass
import dev.phoneagent.core.tool.ScreenPresenter
import dev.phoneagent.core.tool.ToolCall
import dev.phoneagent.core.tool.ToolContext
import dev.phoneagent.core.tool.ToolGroup
import dev.phoneagent.core.tool.ToolResult
import dev.phoneagent.core.tool.ToolStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `phone_observe(detail?, image?)` (phase1 §5): reads the screen as one page. A frame, with
 * Set-of-Mark boxes when the port could draw them ([Frame.som]; the text says which), is asked for
 * when `image=true`, or when `image` is absent and the tree is not [TreeQuality.GOOD]; never when
 * the main model reads no images ([ScreenPresenter.vision], the same capability the page notes
 * follow), the page is FLAG_SECURE (the text says why) or withheld. The description is one text
 * whatever the capability is (tool definitions are part of the cached prompt prefix): it states
 * the condition instead.
 *
 * A frame comes only with [ToolContext.framedSnapshot], which reads the screen again around the
 * capture (policy-rules §6.4): the page shown is then that screen, with the frame when one went
 * with it, else "（截不到图）"; any failure of the capture reads the same.
 */
internal class PhoneObserveTool(
    private val presenter: ScreenPresenter,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "phone_observe",
        description =
            "读当前屏幕，返回这一屏的元素列表（格式见系统提示）。detail=full 列出全部元素和完整文字。" +
                "模型能看图时，image=true 附一张截图，画得上框时带编号框；界面元素读不全且模型能看图时会自动附图。" +
                "动作类工具的结果里已经带了动作后的画面，通常不用紧接着再读屏。",
        mask = presenter::mask,
        declare = {
            string("detail", "brief（默认）控制长度：长文字截断，太长的页面折叠；full 列出全部元素和完整文字。", enum = DETAILS.keys.toList())
            boolean("image", "true 附截图，false 不附；不填时界面读不全才附。模型不看图时不会附图。")
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
        val detail = args.choice("detail", DETAILS) ?: SnapshotDetail.BRIEF
        val image = args.boolean("image")
        return when (val parsed = args.finish(USAGE, detail)) {
            is Parsed.Invalid -> ToolResults.invalid(parsed.message)
            is Parsed.Ok -> observe(ctx, parsed.value, image)
        }
    }

    private suspend fun observe(
        ctx: ToolContext,
        detail: SnapshotDetail,
        image: Boolean?,
    ): ToolResult {
        val screen = ctx.screen ?: return ToolResults.NO_SCREEN
        val options = SnapshotOptions(detail)
        return when (val read = screenCall { screen.snapshot(options) }) {
            is ScreenCall.Unavailable -> ToolResults.unavailable(read.error, presenter)
            is ScreenCall.Done -> withImage(ctx, read.value, options, image)
        }
    }

    private suspend fun withImage(
        ctx: ToolContext,
        snapshot: ScreenSnapshot,
        options: SnapshotOptions,
        image: Boolean?,
    ): ToolResult {
        val wanted = image ?: (snapshot.quality != TreeQuality.GOOD)
        val skipped =
            when {
                // Nothing of a withheld screen is shown, not even whether a frame was taken.
                ScreenFlag.WITHHELD in snapshot.flags -> ""

                !wanted -> ""

                !presenter.vision -> "\n（当前模型不看图，没有附截图）"

                ScreenFlag.SECURE in snapshot.flags -> "\n（这一页禁止截屏，没有附截图）"

                else -> null
            }
        if (skipped !=
            null
        ) {
            return result(snapshot, listOf(Part.Text(presenter.page(snapshot, options.detail) + skipped)))
        }
        return when (val framed = screenCall { ctx.framedSnapshot(options, som = true) }) {
            is ScreenCall.Unavailable -> ToolResults.unavailable(framed.error, presenter)
            is ScreenCall.Done -> framed(ctx, framed.value, options.detail)
        }
    }

    /** The page of the screen read after the capture, with the frame that went with it. */
    private suspend fun framed(
        ctx: ToolContext,
        framed: FramedSnapshot,
        detail: SnapshotDetail,
    ): ToolResult {
        val shown = framed.snapshot
        val page = presenter.page(shown, detail)
        val frame = framed.frame
        val content =
            when {
                ScreenFlag.WITHHELD in shown.flags -> listOf(Part.Text(page))
                frame == null -> listOf(Part.Text("$page\n（截不到图）"))
                else -> attach(ctx, page, frame)
            }
        return result(shown, content)
    }

    private fun result(
        shown: ScreenSnapshot,
        content: List<Part>,
    ): ToolResult {
        val details =
            buildJsonObject {
                put(ToolResult.SCREEN, shown.id.value)
                put("quality", shown.quality.name)
                put("imageAttached", content.any { it is Part.Image })
            }
        return ToolResult(ToolStatus.OK, content, details)
    }

    private suspend fun attach(
        ctx: ToolContext,
        page: String,
        frame: Frame,
    ): List<Part> {
        val blob = ctx.storeBlob(frame.bytes)
        return listOf(
            Part.Text("$page\n" + if (frame.som) BOXED else UNBOXED),
            Part.Image(blob, frame.mime, frame.width, frame.height),
        )
    }

    private companion object {
        const val USAGE = "phone_observe(detail=brief/full?, image?)"
        const val BOXED = "附图：带编号框的截图，框上的编号就是元素的 @e 编号。"

        // No snapshot before the frame, or boxes that cannot be placed (ScreenPort.frame).
        const val UNBOXED = "附图：这张截图没有编号框，按坐标和上面的元素对照。"
    }
}

private val DETAILS = linkedMapOf("brief" to SnapshotDetail.BRIEF, "full" to SnapshotDetail.FULL)
