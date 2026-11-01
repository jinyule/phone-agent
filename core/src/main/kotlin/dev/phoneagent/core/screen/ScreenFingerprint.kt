package dev.phoneagent.core.screen

import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.UiElement
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Computes [ScreenSnapshot.fingerprint]: equal fingerprints mean nothing visible changed.
 *
 * Covers the foreground app and activity, and for every element its window, role, name, value,
 * hint, states, secret flag and bounds, plus OCR text and position. Refs, parents and resource ids
 * are left out: renumbering or restructuring the tree is not a visible change. The result is the
 * first 16 hex characters of a SHA-256 over an unambiguous (length-prefixed) encoding.
 */
public object ScreenFingerprint {
    /** Fingerprint of a screen showing [elements] (and [ocr], when OCR ran) in [pkg] / [activity]. */
    public fun of(
        pkg: String?,
        activity: String?,
        elements: List<UiElement>,
        ocr: List<OcrLine>? = null,
    ): String {
        val canonical = StringBuilder()
        canonical.field(pkg).field(activity)
        for (element in elements) {
            canonical
                .append('E')
                .field(element.window?.toString())
                .field(element.role.name)
                .field(element.name)
                .field(element.value)
                .field(element.hint)
                .field(
                    element.states
                        .map { it.name }
                        .sorted()
                        .joinToString(","),
                ).field(element.secret.toString())
                .field(element.bounds.let { "${it.left},${it.top},${it.right},${it.bottom}" })
        }
        for (line in ocr.orEmpty()) {
            canonical
                .append('O')
                .field(line.text)
                .field(line.bounds.let { "${it.left},${it.top},${it.right},${it.bottom}" })
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
        return HexFormat.of().formatHex(digest).take(LENGTH)
    }

    private fun StringBuilder.field(value: String?): StringBuilder =
        if (value == null) append("-;") else append(value.length).append(':').append(value).append(';')

    private const val LENGTH = 16
}
