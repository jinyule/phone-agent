package dev.phoneagent.core.tools

import dev.phoneagent.core.ids.SnapshotId
import dev.phoneagent.core.ports.ElementRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArgReaderTest {
    private fun reader(
        json: String,
        vararg known: String,
    ) = ArgReader(Json.decodeFromString<JsonObject>(json), known.toSet())

    /** The problem lines of [reader] after reading. */
    private fun problems(reader: ArgReader): List<String> =
        when (val parsed = reader.finish("usage()", Unit)) {
            is Parsed.Ok -> {
                emptyList()
            }

            is Parsed.Invalid -> {
                parsed.message
                    .lines()
                    .drop(1)
                    .dropLast(1)
                    .map { it.removePrefix("- ") }
            }
        }

    @Test
    fun `unknown names are problems, sorted`() {
        val args = reader("""{"b":1,"a":2,"x":3}""", "x")
        assertEquals(listOf("不认识的参数 `a`", "不认识的参数 `b`"), problems(args))
    }

    @Test
    fun `finish lists every problem with the usage, or asks for completeness`() {
        val args = reader("{}", "a")
        args.problem("第一个")
        args.problem("第二个")
        assertEquals(Parsed.Invalid("参数有误，这一步没有执行：\n- 第一个\n- 第二个\n用法：f(a)"), args.finish("f(a)", Unit))
        assertEquals(Parsed.Invalid("参数有误，这一步没有执行：\n- 参数不完整\n用法：f(a)"), reader("{}", "a").finish<Unit>("f(a)", null))
        assertEquals(Parsed.Ok(7), reader("{}", "a").finish("f(a)", 7))
    }

    @Test
    fun `strings - absent, null, required, blank, too long, not a scalar`() {
        val args =
            reader(
                """{"a":"文字","n":null,"blank":"  ","long":"12345","obj":{},"arr":[1]}""",
                "a",
                "n",
                "blank",
                "long",
                "obj",
                "arr",
                "m",
            )
        assertEquals("文字", args.string("a"))
        assertNull(args.string("n"))
        assertFalse(args.has("n"))
        assertTrue(args.has("a"))
        assertNull(args.string("m"))
        assertNull(args.string("m", required = true))
        assertNull(args.string("blank"))
        assertEquals("  ", args.string("blank", allowBlank = true))
        assertNull(args.string("long", max = 4))
        assertNull(args.string("obj"))
        assertNull(args.string("arr"))
        assertEquals(
            listOf(
                "缺少 `m`",
                "`blank` 不能为空",
                "`long` 太长（最多 4 个字）",
                "`obj` 应该是单个值，不是对象或数组",
                "`arr` 应该是单个值，不是对象或数组",
            ),
            problems(args),
        )
    }

    @Test
    fun `integers - numbers, integral floats and numeric strings, within range`() {
        val args =
            reader("""{"a":5,"b":5.0,"c":"7","d":5.5,"e":"x","f":1000,"g":true}""", "a", "b", "c", "d", "e", "f", "g")
        val range = 0L..999L
        assertEquals(5L, args.integer("a", range))
        assertEquals(5L, args.integer("b", range))
        assertEquals(7L, args.integer("c", range))
        assertNull(args.integer("d", range))
        assertNull(args.integer("e", range))
        assertNull(args.integer("f", range))
        assertNull(args.integer("g", range))
        assertNull(args.integer("missing", range, required = true))
        assertEquals(
            listOf(
                "`d` 应该是 0–999 的整数（收到 5.5）",
                "`e` 应该是 0–999 的整数（收到 \"x\"）",
                "`f` 应该是 0–999 的整数（收到 1000）",
                "`g` 应该是 0–999 的整数（收到 true）",
                "缺少 `missing`",
            ),
            problems(args),
        )
    }

    @Test
    fun `booleans and choices`() {
        val args = reader("""{"a":true,"b":"false","c":"yes","d":"up","e":"sideways"}""", "a", "b", "c", "d", "e")
        assertEquals(true, args.boolean("a"))
        assertEquals(false, args.boolean("b"))
        assertNull(args.boolean("c"))
        assertNull(args.boolean("missing"))
        val options = linkedMapOf("up" to 1, "down" to 2)
        assertEquals(1, args.choice("d", options))
        assertNull(args.choice("e", options))
        assertNull(args.choice("missing", options))
        assertNull(args.choice("gone", options, required = true))
        assertEquals(
            listOf("`c` 应该是 true 或 false（收到 \"yes\"）", "`e` 只能是 up / down（收到 \"sideways\"）", "缺少 `gone`"),
            problems(args),
        )
    }

    @Test
    fun `string arrays must be distinct, short and few`() {
        fun read(json: String) =
            reader("""{"o":$json}""", "o").let {
                it.strings("o", maxItems = 2, max = 3) to
                    problems(it)
            }

        assertEquals(listOf("甲", "乙") to emptyList<String>(), read("""["甲","乙"]"""))
        assertEquals(null to emptyList<String>(), read("null"))
        val bad = listOf("""["甲","乙","丙"]""", """["甲","甲"]""", """["甲",1]""", """[""]""", """["四个字呀"]""", "\"甲\"", "{}")
        for (json in bad) {
            val (value, problems) = read(json)
            assertNull(value, json)
            assertEquals(listOf("`o` 应该是不重复的文字数组（最多 2 项，每项最多 3 个字）"), problems, json)
        }
    }

    @Test
    fun `refs accept @e5, e5 and 5`() {
        val args =
            reader(
                """{"a":"@e5","b":"e12","c":"7","d":" @e3 ","e":"e0","f":"按钮","g":"0"}""",
                "a",
                "b",
                "c",
                "d",
                "e",
                "f",
                "g",
            )
        assertEquals(ElementRef("e5"), args.ref("a"))
        assertEquals(ElementRef("e12"), args.ref("b"))
        assertEquals(ElementRef("e7"), args.ref("c"))
        assertEquals(ElementRef("e3"), args.ref("d"))
        assertNull(args.ref("e"))
        assertNull(args.ref("f"))
        assertNull(args.ref("g"))
        assertNull(args.ref("missing"))
        assertEquals(
            listOf(
                "`e` 应该是最近一屏里的元素编号，例如 @e5（收到 \"e0\"）",
                "`f` 应该是最近一屏里的元素编号，例如 @e5（收到 \"按钮\"）",
                "`g` 应该是最近一屏里的元素编号，例如 @e5（收到 \"0\"）",
            ),
            problems(args),
        )
    }

    @Test
    fun `snapshot ids are required, single tokens and bounded`() {
        val long = "s" + "1".repeat(300)
        val args = reader("""{"a":" s12 ","b":"s 12","c":"$long"}""", "a", "b", "c")
        assertEquals(SnapshotId("s12"), args.snapshot("a"))
        assertNull(args.snapshot("b"))
        assertNull(args.snapshot("c"))
        assertNull(args.snapshot("missing"))
        assertEquals(
            listOf(
                "`b` 应该是最近一屏的编号，例如 s12（收到 \"s 12\"）",
                "`c` 应该是最近一屏的编号，例如 s12（收到 \"${long.take(40)}…\"）",
                "缺少 `missing`",
            ),
            problems(args),
        )
    }
}
