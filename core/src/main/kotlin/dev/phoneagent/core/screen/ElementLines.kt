package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.SnapshotDetail
import dev.phoneagent.core.ports.UiElement

/**
 * Renders one element as one line of the screen format (phase1 §4), e.g.
 * `@e10 ⚠ 按钮 "发送" (930,955)` or `@e9 输入框 (提示:"发送消息") {可编辑,焦点} (480,955)`.
 * All text is put on one line and masked ([SensitiveNumbers]); at `brief` (pages and diffs) it is
 * clipped to [ScreenFormatConfig.labelMax] and [ScreenFormatConfig.rowMax], at `full` it is kept
 * whole. Secret values never appear.
 */
internal class ElementLines(
    config: ScreenFormatConfig,
    detail: SnapshotDetail,
) {
    private val labelMax = if (detail == SnapshotDetail.FULL) Int.MAX_VALUE else config.labelMax
    private val rowMax = if (detail == SnapshotDetail.FULL) Int.MAX_VALUE else config.rowMax

    /**
     * The line of [element]; [absorbed] are texts of plain children merged into its label, [added]
     * prints `+` (new since the previous screen) and [risky] prints `⚠`.
     */
    fun element(
        element: UiElement,
        absorbed: List<String>,
        added: Boolean,
        risky: Boolean,
    ): String =
        buildString {
            append('@').append(element.ref.value)
            if (added) append(" +")
            if (risky) append(" ⚠")
            ROLE_WORDS[element.role]?.let { append(' ').append(it) }
            label(element, absorbed)?.let { append(" \"").append(it).append('"') }
            val value = element.value?.let(::clean)?.takeIf { it.isNotEmpty() && !element.secret }
            value?.let { append(" 值:\"").append(it).append('"') }
            val hint = element.hint?.let(::clean)?.takeIf { it.isNotEmpty() && it != element.name?.let(::clean) }
            if (value == null && hint != null) append(" (提示:\"").append(hint).append("\")")
            states(element).takeIf { it.isNotEmpty() }?.let { append(" {").append(it.joinToString(",")).append('}') }
            if (element.isActionable()) {
                val center = element.bounds.center()
                append(" (")
                    .append(center.x)
                    .append(',')
                    .append(center.y)
                    .append(')')
            }
        }

    /** The header line of a scrollable container, e.g. `↕ 列表 @e5 "聊天记录"`; children follow indented. */
    fun list(
        element: UiElement,
        added: Boolean,
    ): String =
        buildString {
            val bounds = element.bounds
            append(if (bounds.right - bounds.left > 2 * (bounds.bottom - bounds.top)) "↔ 列表 @" else "↕ 列表 @")
            append(element.ref.value)
            if (added) append(" +")
            element.name
                ?.let(::clean)
                ?.takeIf { it.isNotEmpty() }
                ?.let { append(" \"").append(it).append('"') }
        }

    /**
     * [text] as shown inside quotes: masked while its line breaks are still there (so a span across
     * lines gets the neutral label), then one line, no ASCII quotes, clipped at `brief`.
     */
    fun clean(text: String): String = clip(oneLine(SensitiveNumbers.mask(text)).replace('"', '＂'), labelMax)

    private fun label(
        element: UiElement,
        absorbed: List<String>,
    ): String? {
        val own = element.name?.let(::clean)?.takeIf { it.isNotEmpty() }
        val extras = absorbed.map(::clean).filter { it.isNotEmpty() && own?.contains(it) != true }.distinct()
        val parts = listOfNotNull(own) + extras
        return if (parts.isEmpty()) null else clip(parts.joinToString(" · "), rowMax)
    }

    private fun states(element: UiElement): List<String> =
        buildList {
            val states = element.states
            if (ElementState.EDITABLE in states) add("可编辑")
            if (element.secret) add("secret")
            if (ElementState.FOCUSED in states) add("焦点")
            checkedWord(element)?.let(::add)
            if (ElementState.SELECTED in states) add("当前")
            if (ElementState.LONG_CLICKABLE in states) add("可长按")
            if (ElementState.DISABLED in states) add("不可用")
        }

    private fun checkedWord(element: UiElement): String? {
        val checked = ElementState.CHECKED in element.states
        return when {
            element.role == ElementRole.SWITCH -> if (checked) "开" else "关"
            element.role == ElementRole.CHECKBOX || element.role == ElementRole.RADIO -> if (checked) "已选" else "未选"
            checked -> "已选"
            else -> null
        }
    }
}

/** Roles printed with a word; plain text, containers and "other" print none. */
private val ROLE_WORDS =
    mapOf(
        ElementRole.BUTTON to "按钮",
        ElementRole.INPUT to "输入框",
        ElementRole.IMAGE to "图片",
        ElementRole.CHECKBOX to "复选框",
        ElementRole.SWITCH to "开关",
        ElementRole.RADIO to "单选",
        ElementRole.LIST to "列表",
        ElementRole.TAB to "标签",
        ElementRole.LINK to "链接",
        ElementRole.PICKER to "滚轮",
        ElementRole.PROGRESS to "进度",
    )
