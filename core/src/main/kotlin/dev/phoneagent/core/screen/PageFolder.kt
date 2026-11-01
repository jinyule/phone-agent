package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.SnapshotDetail

/**
 * Fits a rendered page into [budget] characters (phase1 §4). The header, the top zone and the
 * bottom (navigation) zone are always kept. Then middle-zone lines are kept in reading order
 * while they fit, then OCR lines; once a line does not fit, it and everything after it is hidden
 * and counted: per scrollable container right after its last shown line
 * (`… 另有 N 项（在 @e5 里滚动查看）`), otherwise once at the end of the middle zone.
 */
internal class PageFolder(
    private val budget: Int,
    private val detail: SnapshotDetail,
) {
    /** The page text: [header], zones from [blocks] (each with its rendered lines), then [ocr]. */
    fun fold(
        header: List<String>,
        blocks: List<Pair<Block, List<String>>>,
        ocr: List<String>,
    ): String {
        val fixed = blocks.filter { it.first.zone != Zone.MIDDLE }.flatMap { it.second }
        val middle = blocks.filter { it.first.zone == Zone.MIDDLE }
        val room = Room(budget - cost(header) - cost(fixed) - Zone.entries.sumOf { it.title.length + 1 })
        room.reserveIfShort(cost(middle.flatMap { it.second } + ocr))
        val kept = HashSet<Pair<Int, Int>>()
        middle.forEachIndexed { b, (_, rendered) ->
            rendered.forEachIndexed { l, line -> if (room.take(line)) kept += b to l }
        }
        val ocrKept = ocr.filter(room::take)
        val out = header.toMutableList()
        for (zone in Zone.entries) {
            val section = if (zone == Zone.MIDDLE) middleSection(middle, kept) else plainSection(blocks, zone)
            if (section.isNotEmpty()) out += listOf(zone.title) + section
        }
        if (ocr.isNotEmpty()) out += listOf(OCR_TITLE) + ocrKept + listOfNotNull(hiddenOcr(ocr.size - ocrKept.size))
        if (blocks.isEmpty() && ocr.isEmpty()) out += EMPTY
        return out.joinToString("\n")
    }

    private fun plainSection(
        blocks: List<Pair<Block, List<String>>>,
        zone: Zone,
    ): List<String> = blocks.filter { it.first.zone == zone }.flatMap { it.second }

    private fun middleSection(
        middle: List<Pair<Block, List<String>>>,
        kept: Set<Pair<Int, Int>>,
    ): List<String> {
        val out = mutableListOf<String>()
        var hiddenElsewhere = 0
        middle.forEachIndexed { b, (block, rendered) ->
            val shown = rendered.indices.filter { (b to it) in kept }
            out += shown.map { rendered[it] }
            val hidden = rendered.size - shown.size
            val list = block.list?.takeIf { 0 in shown }
            when {
                hidden == 0 -> Unit
                list != null -> out += "  " + hiddenInList(hidden, list)
                else -> hiddenElsewhere += hidden
            }
        }
        if (hiddenElsewhere > 0) out += hiddenOutside(hiddenElsewhere)
        return out
    }

    private fun hiddenInList(
        count: Int,
        list: ElementRef,
    ): String = "… 另有 $count 项（在 @$list 里滚动查看）"

    private fun hiddenOutside(count: Int): String =
        when (detail) {
            SnapshotDetail.BRIEF -> "… 另有 $count 项（phone_observe detail=full 可看全部）"
            SnapshotDetail.FULL -> "… 另有 $count 项（超出长度上限，滚动后再看）"
        }

    private fun hiddenOcr(count: Int): String? = if (count > 0) "… 另有 $count 行屏上文字" else null

    private fun cost(lines: List<String>): Int = lines.sumOf { it.length + 1 }

    /** Characters left; once a line is refused, every later line is refused too (no gaps). */
    private class Room(
        private var left: Int,
    ) {
        private var reserve = 0
        private var open = true

        /** Keeps [FOLD_RESERVE] free for fold lines when [wanted] characters do not all fit. */
        fun reserveIfShort(wanted: Int) {
            if (wanted > left) reserve = FOLD_RESERVE
        }

        /** Whether [line] still fits; if so, its room is used up. */
        fun take(line: String): Boolean {
            open = open && line.length + 1 + reserve <= left
            if (open) left -= line.length + 1
            return open
        }
    }

    private companion object {
        /** Room kept for the fold lines once anything has to be hidden. */
        const val FOLD_RESERVE = 120
        const val OCR_TITLE = "[屏上文字 · OCR，树里没有]"
        const val EMPTY = "（没有读到界面元素）"
    }
}
