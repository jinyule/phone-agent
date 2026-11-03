package dev.phoneagent.core.model.anthropic

import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.anthropic.Kit.enqueueError
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * `Bearer <token>` right after an escape the provider wrote as text, a backslash and a letter
 * (model-layer §2 rule 4, fix-list r9 S2): in a message field, an error object without one, an
 * error page that is not JSON, a redirect target. The Bearer rule needs no word boundary before
 * the word, so the letter that ends the escape does not keep the token from being masked; the
 * words before it stay. Words ending in `bearer` in a row before the token are masked with it
 * (fix-list r10 S1), so no `Bearer` passes for the token. The price, pinned here: a word that ends
 * in `bearer` masks the word after it too. The token is a placeholder this request did not send.
 */
class BearerAfterEscapeTest {
    private val server = Kit.loopbackServer()

    @AfterEach
    fun closeServer() = server.close()

    /** Where the provider's text goes, and what it says before the escape. */
    enum class Place(
        val prefix: String,
    ) {
        MESSAGE("upstream:"),
        NO_MESSAGE("upstream:"),
        ERROR_PAGE("upstream:"),
        REDIRECT("x"),
    }

    /** One case: [place] and the letter of the escape before `Bearer`. */
    enum class Cell(
        val place: Place,
        val letter: String,
    ) {
        MESSAGE_NEWLINE(Place.MESSAGE, "n"),
        MESSAGE_TAB(Place.MESSAGE, "t"),
        NO_MESSAGE_NEWLINE(Place.NO_MESSAGE, "n"),
        ERROR_PAGE_NEWLINE(Place.ERROR_PAGE, "n"),
        REDIRECT_TAB(Place.REDIRECT, "t"),
    }

    /** The error of a call answered with [text] in [place]. */
    private suspend fun failure(
        place: Place,
        text: String,
    ): ModelError {
        val quoted = JsonPrimitive(text)
        when (place) {
            Place.MESSAGE -> {
                server.enqueueError(
                    500,
                    """{"type":"error","error":{"type":"api_error","message":$quoted}}""",
                )
            }

            Place.NO_MESSAGE -> {
                server.enqueueError(
                    500,
                    """{"type":"error","error":{"type":"api_error","note":$quoted}}""",
                )
            }

            Place.ERROR_PAGE -> {
                server.enqueueError(502, "<html>$text</html>")
            }

            Place.REDIRECT -> {
                server.enqueueError(307, "", "Location" to "https://proxy.example.com/v1?note=$text")
            }
        }
        return assertThrows<ModelError> { Kit.model(server).complete(Kit.request()) }
    }

    @ParameterizedTest
    @EnumSource(Cell::class)
    fun `a Bearer token right after an escape the provider wrote is masked`(cell: Cell) =
        runTest {
            val escape = BS + cell.letter
            val error = failure(cell.place, cell.place.prefix + escape + "Bearer " + TOKEN)
            val printed = "${error.message}\n${error.stackTraceToString()}"
            assertFalse(
                TOKEN in printed || TOKEN in JsonEscapes.lenientDecode(printed),
                "$cell: the error shows the token",
            )
            val masked = cell.place.prefix + escape + "Bearer ***"
            // An error object without a message is shown written back: its backslash escaped again.
            val writtenBack = cell.place == Place.NO_MESSAGE
            val shown = if (writtenBack) JsonPrimitive(masked).toString().removeSurrounding("\"") else masked
            assertTrue(error.message.orEmpty().contains(shown), "$cell: ${error.message}")
        }

    /** Words ending in `bearer` in a row before the token; the message starts with [kept] and ends with [after]. */
    enum class Run(
        val before: String,
        val kept: String,
        val after: String,
    ) {
        /** A word that ends in `bearer`, then `Bearer`: shown since the word boundary went (r9). */
        FORBEARER_BEARER("the forbearer Bearer ", "the forbearer ", " applies"),

        /** A key sent with the scheme already in it, echoed back: shown before r9 too. */
        BEARER_BEARER("rejected Bearer Bearer ", "rejected Bearer", ""),

        /** Three in a row: the run is any length, not one word more. */
        BEARER_BEARER_BEARER("rejected Bearer Bearer Bearer ", "rejected Bearer", ""),
    }

    @ParameterizedTest
    @EnumSource(Run::class)
    fun `a token after a run of bearer words is masked`(run: Run) =
        runTest {
            val error = failure(Place.MESSAGE, run.before + TOKEN + run.after)
            val printed = "${error.message}\n${error.stackTraceToString()}"
            assertFalse(
                TOKEN in printed || TOKEN in JsonEscapes.lenientDecode(printed),
                "$run: the error shows the token",
            )
            val message = error.message.orEmpty()
            assertTrue(
                message.startsWith("server error 500: " + run.kept) && message.endsWith(run.after),
                "$run: $message",
            )
        }

    /**
     * An error that fills the read ([ERROR_BODY_LIMIT], less [WRAPPING] for what holds the text)
     * with `Bearer ` and ends in the token: still a [ModelError], the run and the token one `***`.
     */
    @ParameterizedTest
    @EnumSource(Place::class)
    fun `a run of Bearer words as long as an error body is masked as one`(place: Place) =
        runTest {
            val run = "Bearer ".repeat((ERROR_BODY_LIMIT - WRAPPING - TOKEN.length) / "Bearer ".length)
            val error = failure(place, run + TOKEN)
            val printed = "${error.message}\n${error.stackTraceToString()}"
            assertFalse(
                TOKEN in printed || TOKEN in JsonEscapes.lenientDecode(printed),
                "$place: the error shows the token",
            )
            val message = error.message.orEmpty()
            assertTrue("Bearer ***" in message && "Bearer Bearer" !in message, "$place: ${message.take(SHOWN)}")
        }

    /** The price of no word boundary before `Bearer`. */
    @Test
    fun `a word that ends in bearer masks the word after it`() =
        runTest {
            val error = failure(Place.MESSAGE, "the forbearer notice applies")
            assertEquals("server error 500: the forbearer *** applies", error.message)
        }

    private companion object {
        /** Backslash, built here: tool inputs decode escapes written out. */
        val BS = Char(BACKSLASH).toString()
        const val BACKSLASH = 92

        /** The token after `Bearer`, of no credential shape. Assembled so scanners do not take it for one. */
        const val TOKEN = "gw-upstream-token-" + "placeholder-0123456789"

        /** What HttpTransport reads of an error body. */
        const val ERROR_BODY_LIMIT = 64 * 1024

        /** Room for the JSON, the HTML tags or the address around the text. */
        const val WRAPPING = 128

        /** How much of a message an assertion shows. */
        const val SHOWN = 200
    }
}
