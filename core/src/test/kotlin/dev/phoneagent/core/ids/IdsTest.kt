package dev.phoneagent.core.ids

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IdsTest {
    @Test
    fun `ids serialize as bare strings and print their value`() {
        assertEquals("\"t-1\"", Json.encodeToString(TaskId("t-1")))
        assertEquals(TaskId("t-1"), Json.decodeFromString<TaskId>("\"t-1\""))
        val all =
            listOf(
                TaskId("a"),
                SessionId("b"),
                ToolCallId("c"),
                SnapshotId("d"),
                PromptId("e"),
                ProviderId("f"),
                ModelId("g"),
            )
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), all.map { it.toString() })
        assertEquals("0".repeat(64), BlobRef("0".repeat(64)).toString())
    }

    @Test
    fun `blank, control-character and over-long ids are rejected`() {
        val makers: List<(String) -> Any> =
            listOf(::TaskId, ::SessionId, ::ToolCallId, ::SnapshotId, ::PromptId, ::ProviderId, ::ModelId)
        for (make in makers) {
            assertThrows<IllegalArgumentException> { make(" ") }
            assertThrows<IllegalArgumentException> { make("a\nb") }
            assertThrows<IllegalArgumentException> { make("x".repeat(257)) }
            make("x".repeat(128))
        }
    }

    @Test
    fun `session ids are single path segments`() {
        listOf("a/b", "..", ".", "a b", "x".repeat(129)).forEach {
            assertThrows<IllegalArgumentException>(it) { SessionId(it) }
        }
        assertEquals("s-1.a_b", SessionId("s-1.a_b").value)
    }

    @Test
    fun `blob refs are lowercase sha256 hex`() {
        listOf("A".repeat(64), "a".repeat(63), "g".repeat(64)).forEach {
            assertThrows<IllegalArgumentException>(it) { BlobRef(it) }
        }
    }

    @Test
    fun `Java callers read every id through getValue`() {
        val classes =
            listOf(
                TaskId::class.java,
                SessionId::class.java,
                ToolCallId::class.java,
                SnapshotId::class.java,
                PromptId::class.java,
                ProviderId::class.java,
                ModelId::class.java,
                BlobRef::class.java,
                dev.phoneagent.core.model.SecretRef::class.java,
                dev.phoneagent.core.ports.ElementRef::class.java,
                dev.phoneagent.core.ports.StoragePath::class.java,
            )
        for (type in classes) {
            val raw = if (type == BlobRef::class.java) "f".repeat(64) else "e1"
            assertEquals(raw, javaView(type, raw), type.simpleName)
        }
    }

    /** What Java code sees: the boxed id and its `getValue()` (Kotlin reads the unboxed value directly). */
    private fun javaView(
        type: Class<*>,
        raw: String,
    ): Any? {
        val boxed = type.getMethod("box-impl", String::class.java).invoke(null, raw)
        return type.getMethod("getValue").invoke(boxed)
    }

    @Test
    fun `invalid ids are rejected when decoded`() {
        assertThrows<IllegalArgumentException> { Json.decodeFromString<TaskId>("\" \"") }
    }
}
