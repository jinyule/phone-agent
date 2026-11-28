package dev.phoneagent.privileged.cap

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * Keeps intents from riding inside an intent. Whoever receives a nested intent may start it, and
 * the helper never checked what it would start or with whose permissions. An intent is refused
 * when it is a chooser, has a selector, or has a ClipData item holding an intent (a ClipData item
 * with a plain URI is fine; the URI-grant flags are dropped separately), and unless every extra
 * is of a type on this list:
 * - null, a primitive (boxed) or a primitive array;
 * - String, CharSequence, Uri, an array of them, or an ArrayList of them or of boxed integers;
 * - a Bundle whose values are again on this list;
 * - an ArrayList of ContentValues whose values are leaves of the first two kinds (the contact
 *   insert's `ContactsContract.Intents.Insert.DATA`).
 *
 * Everything else is refused, whatever it holds: any other Parcelable or Parcelable array,
 * SparseArray, Map, Serializable, ClipData, IBinder. A type gets through only by being added here
 * together with a check of what it contains. Bundles, arrays, lists and ContentValues share one
 * nesting limit of [MAX_DEPTH]. Extras that cannot be read are refused.
 */
internal object NestedIntents {
    private const val MAX_DEPTH = 8

    /** Why [intent] may carry another intent, or null when it cannot. */
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
        bundle.keySet().firstNotNullOfOrNull { key ->
            @Suppress("DEPRECATION") // Only the untyped getter reads a value of unknown type.
            inValue(bundle.get(key), "$path.$key", depth + 1)
        }

    /** Why [value] is not on the list, or null when it is (and so is everything inside it). */
    private fun inValue(
        value: Any?,
        path: String,
        depth: Int,
    ): String? =
        when {
            depth > MAX_DEPTH -> "$path is nested more than $MAX_DEPTH levels deep"
            value == null || isLeaf(value) -> null
            value is Bundle -> inBundle(value, path, depth)
            value is Array<*> -> inElements(value.asList(), path, ::isTextOrUri)
            value is ArrayList<*> -> inList(value, path, depth)
            else -> "$path is a ${value.javaClass.name}, not a type the helper passes on"
        }

    private fun inList(
        list: ArrayList<*>,
        path: String,
        depth: Int,
    ): String? =
        if (list.any { it is ContentValues }) {
            list.withIndex().firstNotNullOfOrNull { (index, element) ->
                if (element is ContentValues) {
                    inContentValues(element, "$path[$index]", depth + 1)
                } else {
                    "$path[$index] is not ContentValues like the other elements"
                }
            }
        } else {
            inElements(list, path) { isTextOrUri(it) || it is Int }
        }

    /** Elements of an array or list are leaves: nothing inside them is looked at. */
    private fun inElements(
        elements: List<*>,
        path: String,
        allowed: (Any) -> Boolean,
    ): String? =
        elements.withIndex().firstNotNullOfOrNull { (index, element) ->
            if (element == null || allowed(element)) {
                null
            } else {
                "$path[$index] is a ${element.javaClass.name}, not a type the helper passes on"
            }
        }

    private fun inContentValues(
        values: ContentValues,
        path: String,
        depth: Int,
    ): String? =
        if (depth > MAX_DEPTH) {
            "$path is nested more than $MAX_DEPTH levels deep"
        } else {
            values.keySet().firstNotNullOfOrNull { key ->
                val value = values.get(key)
                if (value == null || isLeaf(value)) {
                    null
                } else {
                    "$path.$key is a ${value.javaClass.name}, not a value ContentValues may pass on"
                }
            }
        }

    private fun isTextOrUri(value: Any): Boolean = value is CharSequence || value is Uri

    private fun isLeaf(value: Any): Boolean = isTextOrUri(value) || value.javaClass in PRIMITIVES

    /** Boxed primitives and primitive arrays; all final classes, so an exact match is enough. */
    private val PRIMITIVES: Set<Class<*>> =
        setOf(
            Boolean::class.javaObjectType,
            Byte::class.javaObjectType,
            Char::class.javaObjectType,
            Short::class.javaObjectType,
            Int::class.javaObjectType,
            Long::class.javaObjectType,
            Float::class.javaObjectType,
            Double::class.javaObjectType,
            BooleanArray::class.java,
            ByteArray::class.java,
            CharArray::class.java,
            ShortArray::class.java,
            IntArray::class.java,
            LongArray::class.java,
            FloatArray::class.java,
            DoubleArray::class.java,
        )
}
