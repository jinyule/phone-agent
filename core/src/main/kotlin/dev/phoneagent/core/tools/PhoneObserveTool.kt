package dev.phoneagent.core.tools

import dev.phoneagent.core.model.Part
import dev.phoneagent.core.ports.Frame
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenPort
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.ScreenUnavailableException
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.SnapshotOptions
import dev.phoneagent.core.ports.TreeQuality
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
 * `phone_observe(detail?, image?)` (phase1 §5): reads the screen as one page. A frame with
 * Set-of-Mark boxes is attached when `image=true`, or when `image` is absent and the tree is not
 * [TreeQuality.GOOD]; never when [vision] is off or the page is FLAG_SECURE (the text says why).
 * The description is one text whatever [vision] is (tool definitions are part of the cached
 * prompt prefix): it states the condition instead.
 */
internal class PhoneObserveTool(
    private val presenter: ScreenPresenter,
    private val vision: Boolean,
    override val timeoutMs: Long,
) : PhaseOneTool(
        name = "phone_observe",
        description =
            "读当前屏幕，返回这一屏的元素列表（格式见系统提示）。detail=full 列出全部元素和完整文字。" +
                "模型能看图时，image=true 附一张带编号框的截图；界面元素读不全且模型能看图时会自动附图。" +
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
        return when (val read = screenCall { screen.snapshot(SnapshotOptions(detail)) }) {
            is ScreenCall.Unavailable -> ToolResults.unavailable(read.error, presenter)
            is ScreenCall.Done -> withImage(ctx, screen, read.value, presenter.page(read.value, detail), image)
        }
    }

    private suspend fun withImage(
        ctx: ToolContext,
        screen: ScreenPort,
        snapshot: ScreenSnapshot,
        page: String,
        image: Boolean?,
    ): ToolResult {
        val wanted = image ?: (snapshot.quality != TreeQuality.GOOD)
        val skipped =
            when {
                !wanted -> ""
                !vision -> "\n（当前模型不看图，没有附截图）"
                ScreenFlag.SECURE in snapshot.flags -> "\n（这一页禁止截屏，没有附截图）"
                else -> null
            }
        val frame = if (skipped == null) screenCall { screen.frame(som = true) } else null
        val content =
            when {
                skipped != null -> listOf(Part.Text(page + skipped))
                frame is ScreenCall.Done && frame.value != null -> attach(ctx, page, frame.value)
                frame is ScreenCall.Unavailable -> listOf(Part.Text("$page\n（截图失败：${failure(frame.error)}）"))
                else -> listOf(Part.Text("$page\n（截不到图）"))
            }
        val details =
            buildJsonObject {
                put("screen", snapshot.id.value)
                put("quality", snapshot.quality.name)
                put("imageAttached", content.any { it is Part.Image })
            }
        return ToolResult(ToolStatus.OK, content, details)
    }

    private suspend fun attach(
        ctx: ToolContext,
        page: String,
        frame: Frame,
    ): List<Part> =
        if (frame.mime in Part.IMAGE_MIMES) {
            val blob = ctx.storeBlob(frame.bytes)
            listOf(
                Part.Text("$page\n附图：带编号框的截图，框上的编号就是元素的 @e 编号。"),
                Part.Image(blob, frame.mime, frame.width, frame.height),
            )
        } else {
            listOf(Part.Text("$page\n（截图格式 ${frame.mime} 不支持，没有附图）"))
        }

    /** Why the frame failed, masked: the message comes from the device. */
    private fun failure(error: ScreenUnavailableException): String = presenter.mask(error.message.orEmpty())

    private companion object {
        const val USAGE = "phone_observe(detail=brief/full?, image?)"
    }
}

private val DETAILS = linkedMapOf("brief" to SnapshotDetail.BRIEF, "full" to SnapshotDetail.FULL)
