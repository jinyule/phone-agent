package dev.phoneagent.app.debug

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import java.security.MessageDigest

/**
 * TEMPORARY, debug builds only (contracts addendum #1 §12): a minimal [SnapshotAnalyzer] so the
 * accessibility ScreenPort can be exercised on a device before core's `screen` package (WP06)
 * provides the real one. WP09 wires the core implementation and deletes this file.
 *
 * - fingerprint: SHA-256 over package, activity, each element's window, role, name, value, states
 *   and bounds, and the OCR lines;
 * - quality: EMPTY without named elements, SPARSE with fewer than three, otherwise GOOD;
 * - diff: identity is window, role, resource id and bounds; same identity with another name,
 *   value or state is a change.
 */
internal object TemporarySnapshotAnalyzer : SnapshotAnalyzer {
    private const val GOOD_NAMED = 3
    private const val FINGERPRINT_BYTES = 12
    private const val BYTE_MASK = 0xff
    private const val HEX = 16

    override fun fingerprint(
        pkg: String?,
        activity: String?,
        elements: List<UiElement>,
        ocr: List<OcrLine>?,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${pkg.orEmpty()}\u0001${activity.orEmpty()}".toByteArray())
        for (e in elements) {
            val b = e.bounds
            val line =
                listOf(
                    e.window,
                    e.role,
                    e.name,
                    e.value,
                    e.states.sorted(),
                    e.secret,
                    "${b.left},${b.top},${b.right},${b.bottom}",
                ).joinToString("\u0001", prefix = "\u0002")
            digest.update(line.toByteArray())
        }
        ocr?.forEach { digest.update("\u0003${it.text}\u0001${it.bounds}".toByteArray()) }
        return digest.digest().take(FINGERPRINT_BYTES).joinToString("") {
            (it.toInt() and BYTE_MASK).toString(HEX).padStart(2, '0')
        }
    }

    override fun quality(
        elements: List<UiElement>,
        surface: SurfaceRef,
        flags: Set<ScreenFlag>,
    ): TreeQuality {
        val named = elements.count { !it.name.isNullOrBlank() || !it.hint.isNullOrBlank() }
        return when {
            named == 0 -> TreeQuality.EMPTY
            named < GOOD_NAMED -> TreeQuality.SPARSE
            else -> TreeQuality.GOOD
        }
    }

    override fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff {
        val beforeByKey = before.elements.groupBy(::identity)
        val afterByKey = after.elements.groupBy(::identity)
        val added = ArrayList<UiElement>()
        val changed = ArrayList<UiElement>()
        for ((key, now) in afterByKey) {
            val was = beforeByKey[key].orEmpty()
            now.forEachIndexed { i, element ->
                val old = was.getOrNull(i)
                when {
                    old == null -> {
                        added += element
                    }

                    old.name != element.name || old.value != element.value || old.states != element.states -> {
                        changed +=
                            element
                    }
                }
            }
        }
        val removed = ArrayList<ElementRef>()
        for ((key, was) in beforeByKey) {
            was.drop(afterByKey[key].orEmpty().size).forEach { removed += it.ref }
        }
        val order = after.elements.withIndex().associate { it.value.ref to it.index }
        val beforeOrder = before.elements.withIndex().associate { it.value.ref to it.index }
        return ScreenDiff(
            added = added.sortedBy { order[it.ref] },
            removed = removed.sortedBy { beforeOrder[it] },
            changed = changed.sortedBy { order[it.ref] },
        )
    }

    private fun identity(e: UiElement): List<Any?> = listOf(e.window, e.role, e.resourceId, e.bounds)
}
