package dev.phoneagent.privileged.cap

import android.content.Intent
import android.os.Bundle

/**
 * Finds intents inside an intent. Whoever receives a nested intent may start it, and the helper
 * never checked what it would start or with whose permissions, so a request carrying one is
 * refused: an Intent, Intent array or Intent list anywhere in the extras (also inside nested
 * Bundles), a chooser, a selector, or a ClipData item holding an intent. Extras that cannot be
 * read count as nested. A ClipData item with a plain URI is fine; the URI-grant flags are dropped
 * separately.
 */
internal object NestedIntents {
    private const val MAX_DEPTH = 8

    /** Why [intent] carries another intent, or null when it does not. */
    fun find(intent: Intent): String? =
        when {
            intent.action == Intent.ACTION_CHOOSER -> "a chooser starts the intent it carries"
            intent.selector != null -> "the intent has a selector"
            inClip(intent) -> "a ClipData item carries an intent"
            else -> inExtras(intent)
        }

    private fun inClip(intent: Intent): Boolean {
        val clip = intent.clipData ?: return false
        return (0 until clip.itemCount).any { clip.getItemAt(it).intent != null }
    }

    // TooGenericExceptionCaught: the app's extras may fail to unparcel in any way; unreadable is refused.
    @Suppress("TooGenericExceptionCaught")
    private fun inExtras(intent: Intent): String? =
        try {
            intent.extras?.let { inBundle(it, "extras", 0) }
        } catch (e: RuntimeException) {
            "the extras cannot be read ($e)"
        }

    private fun inBundle(
        bundle: Bundle,
        path: String,
        depth: Int,
    ): String? =
        if (depth > MAX_DEPTH) {
            "$path is nested more than $MAX_DEPTH levels deep"
        } else {
            bundle.keySet().firstNotNullOfOrNull { key ->
                @Suppress("DEPRECATION") // Only the untyped getter reads a value of unknown type.
                inValue(bundle.get(key), "$path.$key", depth)
            }
        }

    private fun inValue(
        value: Any?,
        path: String,
        depth: Int,
    ): String? =
        when (value) {
            is Intent -> "$path is an intent"
            is Bundle -> inBundle(value, path, depth + 1)
            is Array<*> -> value.firstNotNullOfOrNull { inValue(it, path, depth + 1) }
            is Collection<*> -> value.firstNotNullOfOrNull { inValue(it, path, depth + 1) }
            else -> null
        }
}
