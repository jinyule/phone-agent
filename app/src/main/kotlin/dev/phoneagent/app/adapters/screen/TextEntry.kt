package dev.phoneagent.app.adapters.screen

import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Enters text into a field with fallbacks (phase1 §3.3, after DSH-Android's TextInput):
 * `ACTION_SET_TEXT` → the accessibility service's input connection (API 33+) → clipboard paste,
 * restoring the clipboard afterwards. After every level the field is read back; as soon as it
 * differs from before, entry stops, so text is never entered twice. The own keyboard (no-UI IME)
 * level arrives in M3.
 *
 * The read-back decides the result: the field shows the request ([Result.Entered], ignoring the
 * spaces, dashes and parentheses formatting fields insert; other rewrites such as "1,000" for
 * "1000" are a mismatch), shows something else ([Result.Mismatch]), or was replaced before it could
 * be read ([Result.Unconfirmed]). Password fields cannot be read back: the first level that
 * delivered, or that changed the mask, is trusted.
 */
internal object TextEntry {
    /** A fallback level. */
    enum class Level { SET_TEXT, IME_CONNECTION, PASTE }

    /** What a level did. */
    sealed interface Sent {
        /** The level delivered the text. */
        data object Delivered : Sent

        /** The level tried, and the field or system refused. */
        data object Refused : Sent

        /** The level does not apply here; [reason] says why. */
        data class Skipped(
            val reason: String,
        ) : Sent
    }

    /** What the field shows when read back. */
    sealed interface Shown {
        /** The field's text ([text] is the mask, or `null`, for password fields). */
        data class Text(
            val text: String?,
        ) : Shown

        /** The field is gone (replaced or closed): it cannot be read back. */
        data object Gone : Shown
    }

    /**
     * One request.
     *
     * @property text what the model asked to enter.
     * @property append keep the field's content and add [text] at the end.
     * @property before what the field showed before.
     */
    class Request(
        val text: String,
        val append: Boolean,
        val before: String?,
    ) {
        /** What the field should show afterwards. */
        val expected: String get() = if (append) before.orEmpty() + text else text
    }

    /** A fallback level bound to one field. */
    fun interface Attempt {
        /** Tries to deliver [request]. */
        suspend fun send(request: Request): Sent
    }

    /** Outcome of [enter]. */
    sealed interface Result {
        /**
         * Text went in through [via] and may be submitted: the field shows the request
         * ([confirmed]), or is a password field that took it (not [confirmed]).
         */
        data class Entered(
            val via: Level,
            val confirmed: Boolean,
        ) : Result

        /** Not entered as requested; the field must not be submitted. */
        sealed interface Failure : Result {
            /** Why, for the model, in English; [submitRequested] adds that nothing was submitted. */
            fun explain(submitRequested: Boolean): String
        }

        /** Text went in through [via], but the field shows [shown] instead of the request. */
        data class Mismatch(
            val via: Level,
            val shown: String,
        ) : Failure {
            override fun explain(submitRequested: Boolean): String =
                "the field shows '${shown.take(MAX_QUOTED)}${if (shown.length > MAX_QUOTED) "…" else ""}', " +
                    "not the requested text${notSubmitted(submitRequested)}"
        }

        /** Text was sent through [via], but the field was replaced before it could be read back. */
        data class Unconfirmed(
            val via: Level,
        ) : Failure {
            override fun explain(submitRequested: Boolean): String =
                "the text was sent, but the field was replaced before it could be checked" +
                    "${notSubmitted(submitRequested)}; look at the screen before typing again"
        }

        /** No level changed the field; [reason] is for the model, in English. */
        data class NotEntered(
            val reason: String,
        ) : Failure {
            override fun explain(submitRequested: Boolean): String = reason
        }
    }

    /** Longest read-back quoted to the model. */
    const val MAX_QUOTED: Int = 200

    private fun notSubmitted(submitRequested: Boolean): String = if (submitRequested) "; it was not submitted" else ""

    // Formatting fields insert while typing (phone numbers, card groups) does not count as a mismatch.
    private val FORMATTING = Regex("[\\s\\-()]")

    /**
     * Runs [levels] in order until the field changes. [read] reads the field back; with
     * `settle = true` it first waits for the app to update the field.
     */
    suspend fun enter(
        request: Request,
        secret: Boolean,
        levels: List<Pair<Level, Attempt>>,
        read: suspend (settle: Boolean) -> Shown,
    ): Result {
        val skipped = ArrayList<String>()
        for ((level, attempt) in levels) {
            val result =
                when (val sent = attempt.send(request)) {
                    is Sent.Skipped -> {
                        null.also { skipped += "${level.name.lowercase()}: ${sent.reason}" }
                    }

                    // Password fields cannot be read back; trust the first level that delivered.
                    Sent.Delivered -> {
                        if (secret) {
                            Result.Entered(
                                level,
                                confirmed = false,
                            )
                        } else {
                            verify(level, request, read)
                        }
                    }

                    Sent.Refused -> {
                        judge(level, read(false), request, secret)
                    }
                }
            if (result != null) return result
        }
        val detail = if (skipped.isEmpty()) "" else " (${skipped.joinToString("; ")})"
        return Result.NotEntered("the field did not accept the text$detail")
    }

    /**
     * Reads the field back right away and, unless it already shows the request, once more after it
     * settled (the app may still be updating or formatting it).
     */
    private suspend fun verify(
        level: Level,
        request: Request,
        read: suspend (settle: Boolean) -> Shown,
    ): Result? {
        val now = judge(level, read(false), request, secret = false)
        return if (now is Result.Entered) now else judge(level, read(true), request, secret = false) ?: now
    }

    /** Whether [shown] matches [expected], ignoring formatting characters. */
    fun matches(
        shown: String?,
        expected: String,
    ): Boolean = shown != null && FORMATTING.replace(shown, "") == FORMATTING.replace(expected, "")

    /** What [shown] says about [level]'s attempt; `null` when the field did not change (try the next level). */
    private fun judge(
        level: Level,
        shown: Shown,
        request: Request,
        secret: Boolean,
    ): Result? =
        when (shown) {
            // The field was replaced (often because input landed and the app rebuilt it): stop,
            // without claiming what it holds.
            Shown.Gone -> {
                Result.Unconfirmed(level)
            }

            is Shown.Text -> {
                when {
                    shown.text == request.before -> null
                    secret -> Result.Entered(level, confirmed = false)
                    matches(shown.text, request.expected) -> Result.Entered(level, confirmed = true)
                    else -> Result.Mismatch(level, shown.text.orEmpty())
                }
            }
        }

    /** What [field] shows now, refreshing it first. */
    fun read(field: AccessibilityNodeInfo): Shown {
        if (!field.refresh()) return Shown.Gone
        return Shown.Text(if (field.isPassword) field.text?.toString() else NodeText.fieldText(field))
    }
}

/** The Android fallback levels of [TextEntry], each bound to one field. */
internal object EntryLevels {
    /** `ACTION_SET_TEXT` with the full expected text. */
    fun setText(field: AccessibilityNodeInfo): TextEntry.Attempt =
        TextEntry.Attempt { request ->
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.expected)
            sentIf(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        }

    /**
     * Commits through the service's input connection. Applies only while [field] holds input focus
     * and the connection is bound to its app; [focus] focuses the field and waits for the app.
     */
    fun imeConnection(
        field: AccessibilityNodeInfo,
        host: ScreenHost,
        focus: suspend () -> Unit,
    ): TextEntry.Attempt =
        TextEntry.Attempt { request ->
            val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            if (supported && !field.isFocused) focus()
            val ime = if (supported) host.imeConnection() else null
            when {
                !supported -> {
                    TextEntry.Sent.Skipped("needs Android 13")
                }

                ime == null -> {
                    TextEntry.Sent.Skipped("no input connection")
                }

                !field.isFocused || ime.editorPackage != field.packageName?.toString() -> {
                    TextEntry.Sent.Skipped("the field does not hold input focus")
                }

                request.append && !placeCursor(field, ime, request.before?.length ?: 0) -> {
                    TextEntry.Sent.Skipped(NO_CURSOR)
                }

                request.append -> {
                    sentIf(ime.commit(request.text, replace = false))
                }

                else -> {
                    sentIf(ime.commit(request.expected, replace = true))
                }
            }
        }

    /**
     * Pastes through the clipboard, which holds the text only for this paste: it is restored once
     * the field changed or [awaitChange] gave up. Skipped when the clipboard cannot be read, since
     * it could not be restored.
     */
    fun paste(
        field: AccessibilityNodeInfo,
        clipboard: ClipboardAccess,
        awaitChange: suspend () -> Unit,
    ): TextEntry.Attempt =
        TextEntry.Attempt { request ->
            val saved = clipboard.save()
            if (saved == null) {
                TextEntry.Sent.Skipped("the clipboard cannot be restored afterwards")
            } else {
                try {
                    pasteHeld(field, clipboard, request, awaitChange)
                } finally {
                    clipboard.restore(saved)
                }
            }
        }

    /**
     * Selects what the paste replaces (the old text, or the end for append), then holds the text
     * on the clipboard and pastes. Nothing is put on the clipboard when the selection is refused.
     */
    private suspend fun pasteHeld(
        field: AccessibilityNodeInfo,
        clipboard: ClipboardAccess,
        request: TextEntry.Request,
        awaitChange: suspend () -> Unit,
    ): TextEntry.Sent {
        val length = request.before?.length ?: 0
        val placed =
            when {
                request.append -> select(field, length, length)
                length > 0 -> select(field, 0, length)
                else -> true
            }
        return when {
            !placed -> {
                TextEntry.Sent.Skipped(NO_CURSOR)
            }

            !clipboard.put(request.text, sensitive = field.isPassword) -> {
                TextEntry.Sent.Skipped("the clipboard is unavailable")
            }

            else -> {
                val pasted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasted) awaitChange()
                sentIf(pasted)
            }
        }
    }

    private fun sentIf(delivered: Boolean): TextEntry.Sent =
        if (delivered) TextEntry.Sent.Delivered else TextEntry.Sent.Refused

    /**
     * Puts the cursor at [offset] through the input connection, else the node, and checks where
     * it ended up (the connection's selection, else the node's); `false` unless confirmed there.
     */
    private fun placeCursor(
        field: AccessibilityNodeInfo,
        ime: ImeConnection,
        offset: Int,
    ): Boolean {
        val asked = ime.setSelection(offset, offset) || select(field, offset, offset)
        return asked && (ime.selection() ?: nodeSelection(field)) == (offset to offset)
    }

    private fun nodeSelection(field: AccessibilityNodeInfo): Pair<Int, Int>? {
        field.refresh()
        val start = field.textSelectionStart
        val end = field.textSelectionEnd
        return if (start < 0 || end < 0) null else start to end
    }

    private fun select(
        field: AccessibilityNodeInfo,
        start: Int,
        end: Int,
    ): Boolean {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    private const val NO_CURSOR = "cannot place the cursor"
}
