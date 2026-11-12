package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ModelError
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.Kit.enqueueError
import dev.phoneagent.core.model.openai.Kit.send
import kotlinx.coroutines.test.runTest
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * An error body of credential-shape near misses (fix-list r10b): what took the replaced regular
 * expressions seconds to read is still a [ModelError] whose message shows the JWT in front masked.
 * The time is not asserted here; [CredentialShapesTest] bounds the reads.
 */
class NearMissErrorBodyTest {
    @StartStop
    private val server = Kit.server()

    private fun model(): ChatModel =
        Kit.adapters(Keys()).unprobed(Kit.profile(server.base()), Kit.MODEL, Kit.capabilities(), Kit.blobs())

    /** A 500 error page: the JWT, then near misses. */
    enum class Body(
        val text: String,
    ) {
        /** Within the read: `eyJ-` near misses up to the limit, masked in full. */
        WITHIN_READ("$TOKEN rejected; " + "eyJ-".repeat((ERROR_BODY_LIMIT - WRAPPING) / 4) + "!"),

        /** Past the read: the read ends in `sk-` near misses and `!`, then the body goes on. */
        PAST_READ("$TOKEN rejected; " + "sk-".repeat((ERROR_BODY_LIMIT - WRAPPING) / 3) + "!".repeat(PAST)),
    }

    @ParameterizedTest
    @EnumSource(Body::class)
    fun `an error body of near misses is a ModelError with the JWT masked`(body: Body) =
        runTest {
            server.enqueueError(500, body.text)
            val error = assertThrows<ModelError> { model().send(Kit.request()) }
            val message = error.message.orEmpty()
            assertTrue(message.startsWith("server error 500: *** rejected; "), "$body: ${message.take(SHOWN)}")
            assertFalse(TOKEN in "$message\n${error.stackTraceToString()}", "$body: the error shows the JWT")
        }

    private companion object {
        /** A JWT placeholder, assembled so secret scanners do not take it for one. */
        val TOKEN = listOf("ey" + "Jnotarealheader", "notarealpayload", "notasignature").joinToString(".")

        /** What HttpTransport reads of an error body. */
        const val ERROR_BODY_LIMIT = 64 * 1024

        /** Room for the JWT and the words before the near misses. */
        const val WRAPPING = 128

        /** Characters of the body past the read. */
        const val PAST = 4000

        /** How much of a message an assertion shows. */
        const val SHOWN = 200
    }
}
