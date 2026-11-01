package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.testing.Screens
import dev.phoneagent.core.testing.Screens.box
import dev.phoneagent.core.testing.Screens.el
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScreenFingerprintTest {
    private val base = Screens.CHAT_ELEMENTS
    private val fp = ScreenFingerprint.of("com.tencent.mm", ".Chat", base)

    @Test
    fun `is 16 lowercase hex characters and deterministic`() {
        assertTrue(Regex("[0-9a-f]{16}").matches(fp), fp)
        assertEquals(fp, ScreenFingerprint.of("com.tencent.mm", ".Chat", base.toList()))
    }

    @Test
    fun `ignores refs, parents and resource ids`() {
        val renumbered =
            base.mapIndexed {
                i,
                e,
                ->
                e.copy(
                    ref =
                        dev.phoneagent.core.ports
                            .ElementRef("b${i + 1}"),
                    parent = null,
                    resourceId = "x$i",
                )
            }
        assertEquals(fp, ScreenFingerprint.of("com.tencent.mm", ".Chat", renumbered))
    }

    @Test
    fun `changes with anything visible`() {
        val field = base.indexOfFirst { it.role == ElementRole.INPUT }

        fun changed(transform: (Int, dev.phoneagent.core.ports.UiElement) -> dev.phoneagent.core.ports.UiElement) =
            ScreenFingerprint.of("com.tencent.mm", ".Chat", base.mapIndexed(transform))
        val variants =
            listOf(
                changed { i, e -> if (i == field) e.copy(value = "十点") else e },
                changed { i, e -> if (i == field) e.copy(hint = "说点什么") else e },
                changed { i, e -> if (i == field) e.copy(states = e.states - ElementState.FOCUSED) else e },
                changed { i, e -> if (i == field) e.copy(value = null, secret = true) else e },
                changed { i, e -> if (i == 0) e.copy(bounds = box(0, 30, 81, 90)) else e },
                changed { i, e -> if (i == 0) e.copy(name = "后退") else e },
                changed { i, e -> if (i == 0) e.copy(role = ElementRole.LINK) else e },
                changed { i, e -> if (i == 0) e.copy(window = 2) else e },
                ScreenFingerprint.of("com.tencent.mm", ".Other", base),
                ScreenFingerprint.of("com.other", ".Chat", base),
                ScreenFingerprint.of(null, null, base),
                ScreenFingerprint.of("com.tencent.mm", ".Chat", base, listOf(OcrLine("开始", box(0, 0, 10, 10)))),
            )
        variants.forEach { assertNotEquals(fp, it) }
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun `OCR text and position count, OCR not run equals none found`() {
        val one = ScreenFingerprint.of(null, null, emptyList(), listOf(OcrLine("开始", box(0, 0, 10, 10))))
        val moved = ScreenFingerprint.of(null, null, emptyList(), listOf(OcrLine("开始", box(0, 0, 10, 11))))
        assertNotEquals(one, moved)
        assertEquals(
            ScreenFingerprint.of(null, null, emptyList(), null),
            ScreenFingerprint.of(null, null, emptyList(), emptyList()),
        )
    }

    @Test
    fun `field boundaries are unambiguous`() {
        val a = el("e1", ElementRole.TEXT, "ab").copy(value = null)
        val b = el("e1", ElementRole.TEXT, "a").copy(value = "b")
        val c = el("e1", ElementRole.TEXT, "a;b").copy(value = null)
        val prints = listOf(a, b, c).map { ScreenFingerprint.of(null, null, listOf(it)) }
        assertEquals(3, prints.toSet().size)
    }
}
