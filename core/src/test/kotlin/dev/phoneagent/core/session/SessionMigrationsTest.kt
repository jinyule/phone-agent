package dev.phoneagent.core.session

import dev.phoneagent.core.model.ContextEditPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The migration chain of this build (`SessionFormat.MIGRATIONS`), one step at a time. */
class SessionMigrationsTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun migrated(text: String): JsonObject = ServerEditFieldsMigration.migrate(json(text))

    private val server = """{"type":"server","clearToolUsesAboveTokens":1,"keepToolUses":3,"compactAboveTokens":null}"""
    private val serverV2 =
        """{"type":"server","clearToolUsesAboveTokens":1,"keepToolUses":3,"compactAboveTokens":null,""" +
            """"clearAtLeastTokens":null,"compactInstructions":null}"""

    @Test
    fun `the chain reads every older version up to the current one`() {
        assertEquals((1 until SessionFormat.CURRENT_VERSION).toList(), SessionFormat.MIGRATIONS.map { it.fromVersion })
        assertEquals(1, SessionCodec().oldestReadableVersion)
    }

    @Test
    fun `v1 server edits of a request header and of a side request get the v2 fields as not sent`() {
        assertEquals(
            json("""{"seq":3,"type":"request/header","data":{"model":"m","contextEdits":$serverV2}}"""),
            migrated("""{"seq":3,"type":"request/header","data":{"model":"m","contextEdits":$server}}"""),
        )
        assertEquals(
            json("""{"seq":4,"type":"side/request","data":{"role":"VISION","request":{"contextEdits":$serverV2}}}"""),
            migrated("""{"seq":4,"type":"side/request","data":{"role":"VISION","request":{"contextEdits":$server}}}"""),
        )
    }

    @Test
    fun `everything else passes unchanged`() {
        val unchanged =
            listOf(
                """{"seq":3,"type":"request/header","data":{"contextEdits":{"type":"client"}}}""",
                """{"seq":3,"type":"request/header","data":{"model":"m"}}""",
                """{"seq":3,"type":"request/header","data":{"contextEdits":"server"}}""",
                """{"seq":4,"type":"side/request","data":{"request":{"contextEdits":{"type":"client"}}}}""",
                """{"seq":4,"type":"side/request","data":{"role":"VISION"}}""",
                """{"seq":4,"type":"side/request","data":{"request":"x"}}""",
                """{"seq":5,"type":"turn/start","data":{"turn":1,"contextEdits":$server}}""",
                """{"seq":6,"type":"request/header"}""",
                """{"seq":6,"type":"request/header","data":[]}""",
            )
        for (line in unchanged) assertEquals(json(line), migrated(line), line)
    }

    @Test
    fun `a field the record already has keeps its value`() {
        val v2 = """{"seq":3,"type":"request/header","data":{"contextEdits":$serverV2}}"""
        assertEquals(json(v2), migrated(v2))
        val set = serverV2.replace(""""clearAtLeastTokens":null""", """"clearAtLeastTokens":7""")
        val line = """{"seq":3,"type":"request/header","data":{"contextEdits":$set}}"""
        assertEquals(json(line), migrated(line))
    }

    @Test
    fun `a v1 side request with server edits reads in the current version`() {
        val record = SessionSamples.records.first { it.event is SideRequest }
        val side = record.event as SideRequest
        val withServer =
            record.copy(
                event =
                    side.copy(
                        request = side.request.copy(contextEdits = ContextEditPolicy.ServerSide(100_000, 3, null)),
                    ),
            )
        val codec = SessionCodec()
        // How v1 wrote it: the same payload without the two fields v2 added.
        val v1Line =
            codec
                .encode(withServer)
                .replace(""","clearAtLeastTokens":null,"compactInstructions":null""", "")
        assertEquals(DecodedLine.Event(withServer), codec.decode(v1Line, version = 1, lineNumber = 2))
    }
}
