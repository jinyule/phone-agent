package dev.phoneagent.app.adapters.screen

import dev.phoneagent.app.adapters.screen.TextEntry.Level
import dev.phoneagent.app.adapters.screen.TextEntry.Result
import dev.phoneagent.app.adapters.screen.TextEntry.Sent
import dev.phoneagent.app.adapters.screen.TextEntry.Shown
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fallback decisions of [TextEntry], against a scripted field. */
class TextEntryTest {
    /** A field whose content each level may change, and a log of what ran. */
    private class Field(
        var text: String?,
    ) {
        val log = ArrayList<String>()
        var gone = false

        fun level(
            level: Level,
            sent: Sent,
            writes: String? = null,
        ): Pair<Level, TextEntry.Attempt> =
            level to
                TextEntry.Attempt {
                    log += level.name
                    if (writes != null) text = writes
                    sent
                }

        val read: suspend (Boolean) -> Shown = { settle ->
            log += if (settle) "read-settled" else "read"
            if (gone) Shown.Gone else Shown.Text(text)
        }
    }

    private fun request(
        text: String,
        before: String? = "",
        append: Boolean = false,
    ) = TextEntry.Request(text, append, before)

    @Test
    fun stopsAtTheFirstLevelThatChangesTheField() =
        runTest {
            val field = Field("")
            val levels =
                listOf(
                    field.level(Level.SET_TEXT, Sent.Delivered, writes = "hello"),
                    field.level(Level.PASTE, Sent.Delivered, writes = "hellohello"),
                )

            val result = TextEntry.enter(request("hello"), secret = false, levels, field.read)

            assertEquals(Result.Entered(Level.SET_TEXT, exact = true), result)
            assertEquals(listOf("SET_TEXT", "read"), field.log)
            assertEquals("hello", field.text)
        }

    @Test
    fun waitsForTheAppOnceBeforeFallingBack() =
        runTest {
            val field = Field("")
            var updated = false
            val lazyField =
                Level.SET_TEXT to TextEntry.Attempt { Sent.Delivered.also { updated = true } }
            val read: suspend (Boolean) -> Shown = { settle -> Shown.Text(if (settle && updated) "hi" else "") }

            val result = TextEntry.enter(request("hi"), secret = false, listOf(lazyField), read)

            assertEquals(Result.Entered(Level.SET_TEXT, exact = true), result)
            assertEquals(emptyList<String>(), field.log)
        }

    @Test
    fun fallsBackWhenADeliveredLevelLeavesTheFieldUnchanged() =
        runTest {
            val field = Field("")
            val levels =
                listOf(
                    field.level(Level.SET_TEXT, Sent.Delivered),
                    field.level(Level.IME_CONNECTION, Sent.Skipped("no input connection")),
                    field.level(Level.PASTE, Sent.Delivered, writes = "hi"),
                )

            val result = TextEntry.enter(request("hi"), secret = false, levels, field.read)

            assertEquals(Result.Entered(Level.PASTE, exact = true), result)
            assertEquals(listOf("SET_TEXT", "read", "read-settled", "IME_CONNECTION", "PASTE", "read"), field.log)
        }

    @Test
    fun aChangeOtherThanTheExpectedTextStillStopsTheChain() =
        runTest {
            // An input mask or autocomplete rewrote the text: typing again would double it.
            val field = Field("")
            val levels =
                listOf(
                    field.level(Level.SET_TEXT, Sent.Delivered, writes = "Hi!"),
                    field.level(Level.PASTE, Sent.Delivered),
                )

            val result = TextEntry.enter(request("hi"), secret = false, levels, field.read)

            assertEquals(Result.Entered(Level.SET_TEXT, exact = false), result)
            assertFalse(field.log.contains("PASTE"))
        }

    @Test
    fun aRefusedLevelThatChangedTheFieldCountsAsEntered() =
        runTest {
            val field = Field("")
            val levels = listOf(field.level(Level.SET_TEXT, Sent.Refused, writes = "hi"))

            assertEquals(
                Result.Entered(Level.SET_TEXT, exact = true),
                TextEntry.enter(request("hi"), secret = false, levels, field.read),
            )
        }

    @Test
    fun reportsEveryLevelWhenNothingChangedTheField() =
        runTest {
            val field = Field("")
            val levels =
                listOf(
                    field.level(Level.SET_TEXT, Sent.Refused),
                    field.level(Level.IME_CONNECTION, Sent.Skipped("needs Android 13")),
                    field.level(Level.PASTE, Sent.Skipped("the clipboard cannot be restored afterwards")),
                )

            val result = TextEntry.enter(request("hi"), secret = false, levels, field.read)

            assertEquals(
                Result.NotEntered(
                    "the field did not accept the text (ime_connection: needs Android 13; " +
                        "paste: the clipboard cannot be restored afterwards)",
                ),
                result,
            )
        }

    @Test
    fun secretFieldsTrustTheFirstDeliveredLevelWithoutReadingBack() =
        runTest {
            val field = Field(null)
            val levels =
                listOf(
                    field.level(Level.SET_TEXT, Sent.Delivered),
                    field.level(Level.PASTE, Sent.Delivered),
                )

            val result = TextEntry.enter(request("1234", before = null), secret = true, levels, field.read)

            assertEquals(Result.Entered(Level.SET_TEXT, exact = false), result)
            assertEquals(listOf("SET_TEXT"), field.log)
        }

    @Test
    fun aFieldThatWasReplacedCountsAsEntered() =
        runTest {
            val field = Field("")
            val levels =
                listOf(
                    field.level(Level.SET_TEXT, Sent.Delivered).also { field.gone = true },
                    field.level(Level.PASTE, Sent.Delivered),
                )

            val result = TextEntry.enter(request("hi"), secret = false, levels, field.read)

            assertEquals(Result.Entered(Level.SET_TEXT, exact = false), result)
            assertEquals(listOf("SET_TEXT", "read"), field.log)
        }

    @Test
    fun appendExpectsTheOldTextFirst() =
        runTest {
            val field = Field("Hello")
            val levels = listOf(field.level(Level.SET_TEXT, Sent.Delivered, writes = "Hello world"))

            val result = TextEntry.enter(request(" world", before = "Hello", append = true), false, levels, field.read)

            assertEquals(Result.Entered(Level.SET_TEXT, exact = true), result)
            assertEquals("Hello world", request(" world", before = "Hello", append = true).expected)
            assertEquals("world", request("world", before = "Hello").expected)
        }

    @Test
    fun formattingInsertedByTheFieldDoesNotCountAsAMismatch() {
        assertTrue(TextEntry.matches("+1 (555) 010-9999", "+15550109999"))
        assertFalse(TextEntry.matches("+1 (555) 010-9998", "+15550109999"))
        assertFalse(TextEntry.matches(null, ""))
    }
}
