package dev.phoneagent.core.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Package rules of `:core` (AGENTS.md §4, ADR 2026-11-01-core-contracts):
 *
 * - **Contract packages** are `core` itself and the packages in [CONTRACT_LAYERS]. A contract package
 *   references only contract packages of a strictly lower layer (no cycles, no upward references).
 * - Every other package is a **capability**: a subpackage of a contract package (`model.openai`,
 *   `policy.rules`) or another top-level package (`storage`, `screen`, `tools`). Its id is its first
 *   two segments under a contract package, else its first segment; deeper packages belong to it.
 * - Contract packages never reference capabilities (so `agent` does not know concrete capabilities),
 *   and capabilities reference only contracts and themselves, never each other. Only the app wires
 *   capabilities together.
 * - The capabilities in [ISOLATED] reference no other core package at all, contracts included: the
 *   built-in adb client (`adb`, phase1 §2.1) is app setup code that must never become a tool
 *   (AGENTS.md §3.4), so it may not even implement a tool contract.
 *
 * References are imports (including wildcards), and fully qualified class names and function calls
 * in code, also with type arguments (`….f<A<B>>(x)`), including code inside string templates
 * (`${…}`). Comments and the text of string and char literals do not count. A file without a
 * package declaration is a violation: it cannot be placed.
 * Known limit: a fully qualified reference to a top-level property (`dev.phoneagent.core.x.value`,
 * not called) is not seen.
 */
class PackageDependencyTest {
    @Test
    fun `core production code follows the package rules`() {
        val scope = Konsist.scopeFromProduction("core")
        assertTrue(scope.files.size > 10, "found only ${scope.files.size} files")
        assertEquals(emptyList<String>(), violations(scope, "core/src/main/kotlin/dev/phoneagent/core/"))
    }

    @Test
    fun `the rules reject each kind of counterexample`() {
        val root = "core/src/test/resources/architecture/counterexample/"
        val scope = Konsist.scopeFromDirectory(root)
        assertEquals(
            listOf(
                "NoPackage.kt: no package declaration",
                "adb/ImplementsTool.kt: isolated capability adb must not use contract tool",
                "agent/KnowsAdapter.kt: contract agent must not use capability model.openai",
                "agent/TemplateBypass.kt: contract agent must not use capability storage",
                "model/openai/KnowsScreen.kt: capability model.openai must not use capability screen",
                "ports/KnowsSession.kt: contract ports (layer 1) must not use session (layer 4)",
                "screen/MarksRisk.kt: capability screen must not use capability policy.rules",
                "tool/UsesAdb.kt: contract tool must not use capability adb",
                "tools/FullyQualified.kt: capability tools must not use capability policy.rules",
                "tools/FunctionCall.kt: capability tools must not use capability policy.rules",
                "tools/GenericCall.kt: capability tools must not use capability policy.rules",
                "tools/RawTemplate.kt: capability tools must not use capability storage",
                "tools/RunsAdb.kt: capability tools must not use capability adb",
            ),
            violations(scope, root),
        )
    }

    @Test
    fun `the adb client is isolated in both directions`() {
        val scope = Konsist.scopeFromProduction("core")
        val adb = Unit.Capability("adb")
        val files = scope.files.map { file -> file.packagee?.name?.let(Unit::of) to referencedUnits(file) }
        assertTrue(files.count { it.first == adb } >= 10, "the adb package is where this test expects it")
        val intoAdb = files.filter { (own, used) -> own != adb && adb in used }.map { it.first }
        val outOfAdb =
            files
                .filter { (own, _) -> own == adb }
                .flatMap { it.second }
                .filter { it != adb }
                .distinct()
        assertEquals(emptyList<Unit?>(), intoAdb, "packages using adb")
        assertEquals(emptyList<Unit>(), outOfAdb, "core packages adb uses")
    }

    @Test
    fun `packages are classified as contracts or capabilities`() {
        assertEquals(Unit.Contract(""), Unit.of(BASE))
        assertEquals(Unit.Contract("session"), Unit.of("$BASE.session"))
        assertEquals(Unit.Capability("model.openai"), Unit.of("$BASE.model.openai.sse"))
        assertEquals(Unit.Capability("storage"), Unit.of("$BASE.storage"))
        assertEquals(null, Unit.of("kotlinx.serialization"))
        assertEquals("$BASE.model", packageOfImport("$BASE.model.Part.Text", wildcard = false))
        assertEquals("$BASE.model", packageOfImport("$BASE.model.Part", wildcard = true))
        assertEquals("$BASE.screen", packageOfImport("$BASE.screen", wildcard = true))
        assertEquals("$BASE.session", packageOfImport("$BASE.session.deriveMessages", wildcard = false))
        assertEquals(
            listOf("$BASE.policy.rules"),
            fullyQualifiedPackages("val x =\n    dev.phoneagent.core.policy.rules\n        .Rules()"),
        )
    }

    @Test
    fun `code inside string templates counts, string text and comments do not`() {
        val q = "\""
        val cases =
            mapOf(
                "val s = ${q}a ${'$'}{dev.phoneagent.core.storage.Files.X} b$q" to listOf("$BASE.storage"),
                "val s = $q$q$q${'$'}{f(${q}x$q) + dev.phoneagent.core.screen.Formatter()}$q$q$q" to
                    listOf("$BASE.screen"),
                "val s = ${q}${'$'}{${q}nested ${'$'}{dev.phoneagent.core.tools.T()}$q}$q" to listOf("$BASE.tools"),
                "val c = '$q'; val d = dev.phoneagent.core.screen.F()" to listOf("$BASE.screen"),
                "val s = $q$q${q}x$q$q$q$q; val d = dev.phoneagent.core.screen.F()" to listOf("$BASE.screen"),
                "val c = '\\''; val d = dev.phoneagent.core.screen.F()" to listOf("$BASE.screen"),
                "val s = ${q}esc \\$q dev.phoneagent.core.screen.F$q" to emptyList(),
                "val s = $q${'$'}name dev.phoneagent.core.screen.F$q" to emptyList(),
                "/* outer /* nested */ dev.phoneagent.core.screen.F */ val x = 1" to emptyList(),
                "// dev.phoneagent.core.screen.F" to emptyList(),
                "val v = dev.phoneagent.core.policy.rules.evaluate(x)" to listOf("$BASE.policy.rules"),
                "val v = dev.phoneagent.core.policy.rules.build { }" to listOf("$BASE.policy.rules"),
                "val v = dev.phoneagent.core.policy.rules.evaluate<Rule>(x)" to listOf("$BASE.policy.rules"),
                "val v = dev.phoneagent.core.policy.rules.build<Map<String, List<Int?>>> { }" to
                    listOf("$BASE.policy.rules"),
                "val v = dev.phoneagent.core.policy.rules.run<(Int) -> Unit>(f)" to listOf("$BASE.policy.rules"),
                "val v = dev.phoneagent.core.policy.rules\n    .evaluate < Rule >\n    (x)" to
                    listOf("$BASE.policy.rules"),
            )
        cases.forEach { (code, expected) -> assertEquals(expected, fullyQualifiedPackages(code), code) }
    }

    private sealed interface Unit {
        data class Contract(
            val name: String,
        ) : Unit

        data class Capability(
            val id: String,
        ) : Unit

        companion object {
            fun of(pkg: String): Unit? {
                val segments =
                    pkg
                        .removePrefix(BASE)
                        .removePrefix(".")
                        .split('.')
                        .filter { it.isNotEmpty() }
                val first = segments.firstOrNull()
                return when {
                    pkg != BASE && !pkg.startsWith("$BASE.") -> null
                    first == null -> Contract("")
                    first !in CONTRACT_LAYERS -> Capability(first)
                    segments.size == 1 -> Contract(first)
                    else -> Capability("$first.${segments[1]}")
                }
            }
        }
    }

    private fun violations(
        scope: KoScope,
        root: String,
    ): List<String> =
        scope.files
            .flatMap { file ->
                val path = file.path.substringAfter(root)
                val declared = file.packagee?.name ?: return@flatMap listOf("$path: no package declaration")
                val own = Unit.of(declared) ?: return@flatMap emptyList()
                referencedUnits(file)
                    .mapNotNull { problem(own, it) }
                    .map { "$path: $it" }
            }.sorted()

    /** The core packages [file] refers to, by import or fully qualified. */
    private fun referencedUnits(file: KoFileDeclaration): List<Unit> {
        val imports = file.imports.map { packageOfImport(it.name, it.isWildcard) }
        return (imports + fullyQualifiedPackages(file.text)).mapNotNull(Unit::of).distinct()
    }

    private fun problem(
        own: Unit,
        used: Unit,
    ): String? =
        when (own) {
            is Unit.Contract -> contractProblem(own, used)
            is Unit.Capability -> capabilityProblem(own, used)
        }

    private fun contractProblem(
        own: Unit.Contract,
        used: Unit,
    ): String? =
        when (used) {
            is Unit.Capability -> {
                "contract ${own.label} must not use capability ${used.id}"
            }

            is Unit.Contract -> {
                val (mine, theirs) = layer(own) to layer(used)
                val allowed = used == own || theirs < mine
                if (allowed) null else "contract ${own.label} (layer $mine) must not use ${used.label} (layer $theirs)"
            }
        }

    private fun capabilityProblem(
        own: Unit.Capability,
        used: Unit,
    ): String? =
        when {
            own.id in ISOLATED && used != own -> "isolated capability ${own.id} must not use ${used.description}"
            used is Unit.Capability && used != own -> "capability ${own.id} must not use capability ${used.id}"
            else -> null
        }

    private val Unit.description: String
        get() =
            when (this) {
                is Unit.Contract -> "contract $label"
                is Unit.Capability -> "capability $id"
            }

    private val Unit.Contract.label: String get() = name.ifEmpty { "core" }

    private fun layer(contract: Unit.Contract): Int =
        if (contract.name.isEmpty()) 0 else CONTRACT_LAYERS.getValue(contract.name)

    private companion object {
        const val BASE = "dev.phoneagent.core"

        /** Capabilities that use no other core package (see the class comment). */
        val ISOLATED = setOf("adb")

        /** Contract packages and their layer; a package may only use strictly lower layers. */
        val CONTRACT_LAYERS =
            mapOf(
                "ids" to 0,
                "model" to 1,
                "ports" to 1,
                "tool" to 2,
                "policy" to 3,
                "session" to 4,
                "agent" to 5,
            )

        /** A dotted name below the base package, as written (spaces around dots already removed). */
        val QUALIFIED = Regex("""\bdev\.phoneagent\.core(?:\.[A-Za-z_][A-Za-z0-9_]*)+""")
        val HEADER_LINE = Regex("""^\s*(package|import)\s.*$""", RegexOption.MULTILINE)

        val AROUND_DOT = Regex("""\s*\.\s*""")

        /**
         * Package part of an import name (Konsist gives wildcard imports without `.*`): everything before
         * the first capitalized segment; otherwise the whole name for a wildcard, else all but the last
         * segment (a top-level function or property).
         */
        fun packageOfImport(
            name: String,
            wildcard: Boolean,
        ): String {
            val segments = name.split('.')
            val firstType = segments.indexOfFirst { it.first().isUpperCase() }
            val end =
                when {
                    firstType >= 0 -> firstType
                    wildcard -> segments.size
                    else -> segments.size - 1
                }
            return segments.take(end).joinToString(".")
        }

        /**
         * Packages of fully qualified references in code, also when formatted across lines: a name
         * reaching a class (its first capitalized segment), or a function that is called, with or
         * without type arguments (`f(…)`, `f { … }`, `f<A<B>>(…)`).
         */
        fun fullyQualifiedPackages(text: String): List<String> {
            val code = AROUND_DOT.replace(HEADER_LINE.replace(CodeOnly(text).code(), ""), ".")
            return QUALIFIED
                .findAll(code)
                .mapNotNull { match ->
                    val segments = match.value.split('.')
                    val firstType = segments.indexOfFirst { it.first().isUpperCase() }
                    when {
                        firstType >= 0 -> segments.take(firstType)
                        isCalled(code, match.range.last + 1) -> segments.dropLast(1)
                        else -> null
                    }?.joinToString(".")
                }.toList()
        }

        /** Whether a call (`(` or `{`) follows at [from], after optional balanced type arguments. */
        private fun isCalled(
            code: String,
            from: Int,
        ): Boolean {
            var at = skipSpaces(code, from)
            if (code.getOrNull(at) == '<') at = skipSpaces(code, endOfTypeArguments(code, at) ?: return false)
            return code.getOrNull(at) == '(' || code.getOrNull(at) == '{'
        }

        /** Index after the `>` closing the type arguments that open at [open], or `null` when they do not close. */
        private fun endOfTypeArguments(
            code: String,
            open: Int,
        ): Int? {
            var depth = 0
            var at = open
            while (at < code.length && code[at] in TYPE_ARGUMENT_CHARS) {
                when {
                    code[at] == '<' -> depth++
                    code[at] == '>' && code.getOrNull(at - 1) != '-' -> depth--
                }
                at++
                if (depth == 0) return at
            }
            return null
        }

        private fun skipSpaces(
            code: String,
            from: Int,
        ): Int {
            var at = from
            while (at < code.length && code[at].isWhitespace()) at++
            return at
        }

        /** What type arguments are written with: names, nesting, nullability, projections, function types. */
        private val TYPE_ARGUMENT_CHARS = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "_.,?*<>()- \n\t@:".toList()).toSet()
    }
}

/**
 * Kotlin source with comments and the text of string and char literals left out. Code inside
 * `${…}` templates is kept, also in raw strings and in strings nested in templates; a simple
 * `$name` template is text. Nested block comments and escapes are handled.
 */
private class CodeOnly(
    private val text: String,
) {
    private val out = StringBuilder()
    private var at = 0

    fun code(): String {
        copyCode(inTemplate = false)
        return out.toString()
    }

    /** Copies code until the end, or past the `}` that closes the template when [inTemplate]. */
    private fun copyCode(inTemplate: Boolean) {
        var depth = 0
        var open = true
        while (open && at < text.length) {
            val c = text[at]
            when {
                text.startsWith("//", at) -> {
                    skipLineComment()
                }

                text.startsWith("/*", at) -> {
                    skipBlockComment()
                }

                text.startsWith(RAW_QUOTE, at) -> {
                    skipString(raw = true)
                }

                c == '"' -> {
                    skipString(raw = false)
                }

                c == '\'' -> {
                    skipChar()
                }

                c == '}' && inTemplate && depth == 0 -> {
                    at++
                    open = false
                }

                else -> {
                    depth += braceChange(c)
                    out.append(c)
                    at++
                }
            }
        }
    }

    private fun skipString(raw: Boolean) {
        at += if (raw) RAW_QUOTE.length else 1
        out.append(' ')
        while (at < text.length && !closes(raw)) {
            when {
                !raw && text[at] == '\\' -> at += 2
                text.startsWith("${'$'}{", at) -> templateCode()
                else -> at++
            }
        }
        // A raw string ends at the last three quotes of a run.
        while (raw && text.startsWith(RAW_QUOTE + "\"", at)) at++
        at += if (raw) RAW_QUOTE.length else 1
        out.append(' ')
    }

    private fun braceChange(c: Char): Int =
        when (c) {
            '{' -> 1
            '}' -> -1
            else -> 0
        }

    private fun closes(raw: Boolean) = if (raw) text.startsWith(RAW_QUOTE, at) else text[at] == '"'

    private fun templateCode() {
        at += 2
        out.append(' ')
        copyCode(inTemplate = true)
        out.append(' ')
    }

    private fun skipChar() {
        at++
        while (at < text.length && text[at] != '\'') at += if (text[at] == '\\') 2 else 1
        at++
    }

    private fun skipLineComment() {
        while (at < text.length && text[at] != '\n') at++
    }

    private fun skipBlockComment() {
        var depth = 0
        do {
            when {
                text.startsWith("/*", at) -> depth++.also { at += 2 }
                text.startsWith("*/", at) -> depth--.also { at += 2 }
                else -> at++
            }
        } while (depth > 0 && at < text.length)
    }

    private companion object {
        const val RAW_QUOTE = "\"\"\""
    }
}
