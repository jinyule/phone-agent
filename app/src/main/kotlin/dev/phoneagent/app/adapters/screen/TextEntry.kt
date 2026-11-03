package dev.phoneagent.app.adapters.screen

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Enters text into a field with fallbacks (phase1 §3.3, after DSH-Android's TextInput):
 * `ACTION_SET_TEXT` → the accessibility service's input connection (API 33+) → clipboard paste,
 * restoring the clipboard afterwards. After every level the field is read back; as soon as it
 * differs from before, entry stops, so text is never entered twice. The own keyboard (no-UI IME)
 * level arrives in M3.
 *
 * The read-back decides the result: the field shows the request ([Result.Entered], up to the
 * [Formatting] its app inserts: spaces only in a number field, whose minus sign is the number's, else
 * spaces, dashes and parentheses; other rewrites such as "1,000" for "1000" are a mismatch), shows
 * something else ([Result.Mismatch]), or was replaced before it could be read ([Result.Unconfirmed]).
 * A field that already shows the request is left alone ([Result.AlreadyShown]): exactly, or up to
 * that formatting in a phone number, number or date field ([formattingOf]), where the app formats
 * what it is given. Only a match is submitted; after a
 * mismatch the model sees what the field shows and decides whether to press ENTER. Password fields
 * cannot be read back: the first level that delivered, or that changed the mask, is trusted, and
 * text is added to a password only where the editor confirms its end.
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
     * @property formatting how the field formats its text ([formattingOf]), so [before] may show
     *   the request formatted; `null` for a field that formats nothing.
     */
    class Request(
        val text: String,
        val append: Boolean,
        val before: String?,
        val formatting: Formatting? = null,
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

        /** The field already showed the request: nothing was typed, and it may be submitted. */
        data object AlreadyShown : Result

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

    /** Why typing failed although the text went in, for the model. */
    fun clipboardLost(submitRequested: Boolean): String =
        "the text was entered, but the user's clipboard could not be restored${notSubmitted(submitRequested)}"

    /** Why appending to a password field failed (its end could not be confirmed), for the model. */
    const val WHOLE_PASSWORD: String = "a password field can only be typed as a whole"

    /** Longest read-back quoted to the model. */
    const val MAX_QUOTED: Int = 200

    private fun notSubmitted(submitRequested: Boolean): String = if (submitRequested) "; it was not submitted" else ""

    /**
     * What an app may insert into a field's text as it formats it (phone numbers, card groups); a
     * request matches what a field shows when the two are equal once it is taken out of both.
     */
    enum class Formatting(
        private val inserted: Regex,
    ) {
        /** Spaces, dashes and parentheses: phone numbers, dates and times, and text read back. */
        SEPARATORS(Regex("[\\s\\-()]")),

        /** Spaces only: numbers, whose minus sign is the number's (fix list r4 C2). */
        SPACES(Regex("\\s")),
        ;

        /** Whether [shown] is [expected] up to this formatting. */
        fun matches(
            shown: String?,
            expected: String,
        ): Boolean = shown != null && inserted.replace(shown, "") == inserted.replace(expected, "")
    }

    /**
     * Runs [levels] in order until the field changes. [read] reads the field back; with
     * `settle = true` it first waits for the app to update the field. A field that is not a
     * password and already shows the request runs no level: exactly the request, or for a field
     * that formats its text ([Request.formatting]), the request up to that formatting. Before typing,
     * an app has formatted nothing, so in any other field a space or dash more or less is a change.
     */
    suspend fun enter(
        request: Request,
        secret: Boolean,
        levels: List<Pair<Level, Attempt>>,
        read: suspend (settle: Boolean) -> Shown,
    ): Result =
        if (!secret && shows(request)) {
            Result.AlreadyShown
        } else {
            fallBack(request, secret, levels, read)
        }

    private suspend fun fallBack(
        request: Request,
        secret: Boolean,
        levels: List<Pair<Level, Attempt>>,
        read: suspend (settle: Boolean) -> Shown,
    ): Result {
        // A password field shows a mask (or nothing): SET_TEXT and paste would write the mask back
        // or replace the password, so appending goes only through the input connection, which
        // types only where the editor confirms the password's end.
        val maskedAppend = secret && request.append && request.before != ""
        val usable = if (maskedAppend) levels.filter { it.first == Level.IME_CONNECTION } else levels
        val skipped = ArrayList<String>()
        for ((level, attempt) in usable) {
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
        return Result.NotEntered(if (maskedAppend) WHOLE_PASSWORD else "the field did not accept the text$detail")
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

    private fun shows(request: Request): Boolean =
        request.before == request.expected || request.formatting?.matches(request.before, request.expected) == true

    /**
     * How [field] formats its text, by its input type: a phone number or a date and time field
     * [Formatting.SEPARATORS], a number field [Formatting.SPACES]; `null` for any other field.
     */
    fun formattingOf(field: AccessibilityNodeInfo): Formatting? =
        when (field.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> Formatting.SEPARATORS
            InputType.TYPE_CLASS_NUMBER -> Formatting.SPACES
            else -> null
        }

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
                    readBack(request).matches(shown.text, request.expected) -> Result.Entered(level, confirmed = true)
                    else -> Result.Mismatch(level, shown.text.orEmpty())
                }
            }
        }

    /** The formatting a read-back after typing allows: the field's own, any other field's app may insert separators. */
    private fun readBack(request: Request): Formatting = request.formatting ?: Formatting.SEPARATORS

    /** What [field] shows now, refreshing it first; a password field's mask, empty while it shows its hint. */
    fun read(field: AccessibilityNodeInfo): Shown {
        if (!field.refresh()) return Shown.Gone
        return Shown.Text(if (field.isPassword) maskOf(field) else NodeText.fieldText(field))
    }

    private fun maskOf(field: AccessibilityNodeInfo): String? {
        val text = field.text?.toString()
        val hint = field.hintText?.toString()?.trim()
        val showsHint = field.isShowingHintText || (!hint.isNullOrEmpty() && text?.trim() == hint)
        return if (showsHint) "" else text
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
     * and the connection is bound to its app; [focus] focuses the field and waits for the app. A
     * password, which cannot be read back, is replaced only once the editor confirms its selection
     * from the start of the text with nothing told after it ([ImeConnection.selectAll]), and then
     * with what follows the selection deleted ([ImeConnection.commitThroughEnd]); else the level is
     * skipped.
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

                request.append && !placeAtEnd(field, ime, request.before) -> {
                    TextEntry.Sent.Skipped(NO_CURSOR)
                }

                request.append -> {
                    sentIf(ime.commit(request.text, replace = false))
                }

                else -> {
                    replaceAll(field, ime, request.expected)
                }
            }
        }

    // Committing over a selection that is not all of a password would leave part of it beside the
    // text: the editor confirms where the selection starts, and what it may hold after the
    // selection without telling is deleted.
    private fun replaceAll(
        field: AccessibilityNodeInfo,
        ime: ImeConnection,
        text: String,
    ): TextEntry.Sent =
        when {
            !field.isPassword -> sentIf(ime.commit(text, replace = true))
            ime.selectAll() == true -> sentIf(ime.commitThroughEnd(text))
            else -> TextEntry.Sent.Skipped(NO_CURSOR)
        }

    /**
     * Pastes through the clipboard, which holds the text only for this paste: it is restored once
     * the field changed or [awaitChange] gave up, and also when the paste fails or is cancelled
     * (that error stays the one thrown). Skipped when the clipboard cannot be read, since it could
     * not be restored. Whether the restore worked is [ClipboardAccess.restore]'s to report. Any
     * error ending the paste is caught only to restore the clip, then rethrown.
     */
    @Suppress("TooGenericExceptionCaught")
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
                val sent =
                    try {
                        pasteHeld(field, clipboard, request, awaitChange)
                    } catch (stopped: Throwable) {
                        // Cancellation included: put the clip back, rethrow the original error.
                        restoreAfter(stopped, clipboard, saved)
                        throw stopped
                    }
                clipboard.restore(saved)
                sent
            }
        }

    /**
     * Selects what the paste replaces (the old text, or the end for append), then holds the text
     * on the clipboard and pastes. Nothing is put on the clipboard unless the field, read again,
     * reports that selection. A password is pasted into only when it shows empty: what it shows
     * otherwise (nothing, or a mask, which need not be as long as the password) does not tell how
     * much to select.
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
                field.isPassword && request.before != "" -> false
                request.append -> select(field, length, length) && nodeSelection(field) == (length to length)
                length > 0 -> select(field, 0, length) && nodeSelection(field) == (0 to length)
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

    /** Restores [saved] after [stopped] ended the paste; anything restoring throws is attached to [stopped]. */
    @Suppress("TooGenericExceptionCaught")
    private fun restoreAfter(
        stopped: Throwable,
        clipboard: ClipboardAccess,
        saved: SavedClip,
    ) {
        try {
            clipboard.restore(saved)
        } catch (alsoFailed: Throwable) {
            // restore() must not throw; if it does, [stopped] stays the primary error.
            stopped.addSuppressed(alsoFailed)
        }
    }

    private fun sentIf(delivered: Boolean): TextEntry.Sent =
        if (delivered) TextEntry.Sent.Delivered else TextEntry.Sent.Refused

    /**
     * Puts the cursor at the end of [field]'s text, [before] being what the field showed; `false`
     * unless confirmed there. A password's mask need not be as long as the password, or is not
     * shown at all, so for a password only the editor's own word that nothing follows the cursor
     * confirms the end; when the length is unknown the cursor is not moved.
     */
    private fun placeAtEnd(
        field: AccessibilityNodeInfo,
        ime: ImeConnection,
        before: String?,
    ): Boolean {
        if (!field.isPassword) return placeCursor(field, ime, before?.length ?: 0)
        val placed = before == null || placeCursor(field, ime, before.length)
        return placed && ime.cursorAtEnd() == true
    }

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

    /** [field]'s selection read afresh, or `null` when it cannot be read: a node not refreshed holds an old one. */
    private fun nodeSelection(field: AccessibilityNodeInfo): Pair<Int, Int>? {
        if (!field.refresh()) return null
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

/** [ClipboardAccess] that remembers whether putting the user's clip back ever failed ([lost]). */
internal class RestoreTracking(
    private val clipboard: ClipboardAccess,
) : ClipboardAccess by clipboard {
    /** Some restore returned `false`. */
    var lost: Boolean = false
        private set

    override fun restore(saved: SavedClip): Boolean = clipboard.restore(saved).also { if (!it) lost = true }
}
