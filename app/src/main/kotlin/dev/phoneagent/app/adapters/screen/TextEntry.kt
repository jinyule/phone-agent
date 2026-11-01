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
        /** Text went in through [via]; [exact] when the field shows exactly the expected text. */
        data class Entered(
            val via: Level,
            val exact: Boolean,
        ) : Result

        /** No level changed the field; [reason] is for the model, in English. */
        data class NotEntered(
            val reason: String,
        ) : Result
    }

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
                    is Sent.Skipped -> null.also { skipped += "${level.name.lowercase()}: ${sent.reason}" }

                    // Password fields cannot be read back; trust the first level that delivered.
                    Sent.Delivered -> if (secret) Result.Entered(level, exact = false) else verify(level, request, read)

                    Sent.Refused -> judge(level, read(false), request)
                }
            if (result != null) return result
        }
        val detail = if (skipped.isEmpty()) "" else " (${skipped.joinToString("; ")})"
        return Result.NotEntered("the field did not accept the text$detail")
    }

    /** Reads the field back right away and, if it did not change yet, once more after it settled. */
    private suspend fun verify(
        level: Level,
        request: Request,
        read: suspend (settle: Boolean) -> Shown,
    ): Result? = judge(level, read(false), request) ?: judge(level, read(true), request)

    /** Whether [shown] matches [expected], ignoring formatting characters. */
    fun matches(
        shown: String?,
        expected: String,
    ): Boolean = shown != null && FORMATTING.replace(shown, "") == FORMATTING.replace(expected, "")

    private fun judge(
        level: Level,
        shown: Shown,
        request: Request,
    ): Result? =
        when (shown) {
            // The field was replaced (often because input landed and the app rebuilt it): stop.
            Shown.Gone -> {
                Result.Entered(level, exact = false)
            }

            is Shown.Text -> {
                when {
                    matches(shown.text, request.expected) -> Result.Entered(level, exact = true)
                    shown.text != request.before -> Result.Entered(level, exact = false)
                    else -> null
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

                request.append -> {
                    moveCursorToEnd(field, request.before)
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

    private suspend fun pasteHeld(
        field: AccessibilityNodeInfo,
        clipboard: ClipboardAccess,
        request: TextEntry.Request,
        awaitChange: suspend () -> Unit,
    ): TextEntry.Sent {
        val held = clipboard.put(request.text, sensitive = field.isPassword)
        if (!held) return TextEntry.Sent.Skipped("the clipboard is unavailable")
        val length = request.before?.length ?: 0
        if (request.append) {
            select(field, length, length)
        } else if (length > 0) {
            select(field, 0, length)
        }
        val pasted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        if (pasted) awaitChange()
        return sentIf(pasted)
    }

    private fun sentIf(delivered: Boolean): TextEntry.Sent =
        if (delivered) TextEntry.Sent.Delivered else TextEntry.Sent.Refused

    private fun moveCursorToEnd(
        field: AccessibilityNodeInfo,
        before: String?,
    ) {
        val length = before?.length ?: 0
        select(field, length, length)
    }

    private fun select(
        field: AccessibilityNodeInfo,
        start: Int,
        end: Int,
    ) {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }
}
