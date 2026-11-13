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
 *   and capabilities reference only contracts and themselves, never each other.
 * - The [ASSEMBLY] package wires capabilities together: the product assembly the app and session
 *   replay share (phase1 §2.1). It may reference every contract and every capability but the
 *   isolated ones, and no other core package references it.
 * - The capabilities in [ISOLATED] reference no other core package at all, contracts included: the
 *   built-in adb client (`adb`, phase1 §2.1) is app setup code that must never become a tool
 *   (AGENTS.md §3.4), so it may not even implement a tool contract.
 *
 * References are imports (including wildcards) and fully qualified names in code, including code
 * inside string templates (`${…}`); names quoted with backticks (`` core.`assembly`.X ``) count as
 * written without them. A fully qualified name is placed in the longest of its prefixes that is a
 * package of core (so a top-level property, `dev.phoneagent.core.x.value`, counts too); one in no
 * known package counts by its first capitalized segment (a class) or when it is called, also with
 * type arguments (`….f<A<B>>(x)`). Comments and the text of string and char literals do not count.
 * A file without a package declaration is a violation: it cannot be placed.
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
                "agent/KnowsAssembly.kt: contract agent must not use the assembly",
                "agent/TemplateBypass.kt: contract agent must not use capability storage",
                "assembly/RunsAdb.kt: the assembly must not use isolated capability adb",
                "model/openai/KnowsScreen.kt: capability model.openai must not use capability screen",
                "ports/KnowsSession.kt: contract ports (layer 1) must not use session (layer 4)",
                "screen/MarksRisk.kt: capability screen must not use capability policy.rules",
                "tool/UsesAdb.kt: contract tool must not use capability adb",
                "tools/BacktickImport.kt: capability tools must not use capability policy.rules",
                "tools/BacktickName.kt: capability tools must not use the assembly",
                "tools/CapabilityProperty.kt: capability tools must not use capability policy.rules",
                "tools/FullyQualified.kt: capability tools must not use capability policy.rules",
                "tools/FunctionCall.kt: capability tools must not use capability policy.rules",
                "tools/GenericCall.kt: capability tools must not use capability policy.rules",
                "tools/KnowsAssembly.kt: capability tools must not use the assembly",
                "tools/PropertyAccess.kt: capability tools must not use the assembly",
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
        val packages = packagesOf(scope)
        val files =
            scope.files.map { file ->
                file.packagee
                    ?.name
                    ?.let(::unquoted)
                    ?.let(Unit::of) to
                    referencedUnits(file, packages)
            }
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
        assertEquals(Unit.Assembly, Unit.of("$BASE.assembly"))
        assertEquals(Unit.Assembly, Unit.of("$BASE.assembly.parts"))
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
    fun `backticks and properties do not hide a reference`() {
        val tick = "`"
        assertEquals("$BASE.policy.rules.Rules", unquoted("$BASE.${tick}policy$tick.${tick}rules$tick.Rules"))
        assertEquals(
            listOf("$BASE.assembly"),
            fullyQualifiedPackages("val v = dev.phoneagent.core.${tick}assembly$tick.assemble(m)"),
        )
        val known = setOf("$BASE.assembly", "$BASE.policy", "$BASE.policy.rules")
        assertEquals(
            listOf("$BASE.assembly", "$BASE.policy.rules"),
            fullyQualifiedPackages(
                "val a = dev.phoneagent.core.assembly.config.ownPackages\n" +
                    "val b = dev.phoneagent.core.policy.rules.tuning.totalsRead",
                known,
            ),
        )
        assertEquals(
            listOf("$BASE.policy.rules"),
            fullyQualifiedPackages("val r = dev.phoneagent.core.policy.rules.Rules()", known),
            "the longest package wins",
        )
        assertEquals(
            emptyList<String>(),
            fullyQualifiedPackages("val p = dev.phoneagent.core.nowhere.value", known),
            "in no known package, a property is not placed",
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

        data object Assembly : Unit

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
                    first == ASSEMBLY -> Assembly
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
                val own = Unit.of(unquoted(declared)) ?: return@flatMap emptyList()
                referencedUnits(file, packagesOf(scope))
                    .mapNotNull { problem(own, it) }
                    .map { "$path: $it" }
            }.sorted()

    /** The core packages [file] refers to, by import or fully qualified; [packages] are those that exist. */
    private fun referencedUnits(
        file: KoFileDeclaration,
        packages: Set<String>,
    ): List<Unit> {
        val imports = file.imports.map { packageOfImport(unquoted(it.name), it.isWildcard) }
        return (imports + fullyQualifiedPackages(file.text, packages)).mapNotNull(Unit::of).distinct()
    }

    /** The packages of core's production code and of [scope]. */
    private fun packagesOf(scope: KoScope): Set<String> =
        (productionPackages + scope.files.mapNotNull { it.packagee?.name?.let(::unquoted) }).toSet()

    private val productionPackages: List<String> by lazy {
        Konsist.scopeFromProduction("core").files.mapNotNull { it.packagee?.name?.let(::unquoted) }
    }

    private fun problem(
        own: Unit,
        used: Unit,
    ): String? =
        when {
            used == Unit.Assembly && own != Unit.Assembly -> "${own.description} must not use the assembly"
            own is Unit.Contract -> contractProblem(own, used)
            own is Unit.Capability -> capabilityProblem(own, used)
            used is Unit.Capability && used.id in ISOLATED -> "the assembly must not use isolated capability ${used.id}"
            else -> null
        }

    private fun contractProblem(
        own: Unit.Contract,
        used: Unit,
    ): String? =
        when (used) {
            is Unit.Assembly -> {
                null // Reported by problem().
            }

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
                is Unit.Assembly -> "the assembly"
            }

    private val Unit.Contract.label: String get() = name.ifEmpty { "core" }

    private fun layer(contract: Unit.Contract): Int =
        if (contract.name.isEmpty()) 0 else CONTRACT_LAYERS.getValue(contract.name)

    private companion object {
        const val BASE = "dev.phoneagent.core"

        /** Capabilities that use no other core package (see the class comment). */
        val ISOLATED = setOf("adb")

        /** The package that wires capabilities together (see the class comment). */
        const val ASSEMBLY = "assembly"

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

        /** [name] without the backticks that quote its segments (`` a.`b`.c `` is `a.b.c`). */
        fun unquoted(name: String): String = name.replace("`", "")

        /**
         * Packages of fully qualified references in code, also when formatted across lines or quoted
         * with backticks: the longest prefix of the name that is one of [packages]; for a name in none
         * of them, the part before its first capitalized segment (a class), or before a function that
         * is called, with or without type arguments (`f(…)`, `f { … }`, `f<A<B>>(…)`).
         */
        fun fullyQualifiedPackages(
            text: String,
            packages: Set<String> = emptySet(),
        ): List<String> {
            val code = AROUND_DOT.replace(unquoted(HEADER_LINE.replace(CodeOnly(text).code(), "")), ".")
            return QUALIFIED
                .findAll(code)
                .mapNotNull { match ->
                    val segments = match.value.split('.')
                    val known = longestPackage(segments, packages)
                    val firstType = segments.indexOfFirst { it.first().isUpperCase() }
                    when {
                        known != null -> known
                        firstType >= 0 -> segments.take(firstType)
                        isCalled(code, match.range.last + 1) -> segments.dropLast(1)
                        else -> null
                    }?.joinToString(".")
                }.toList()
        }

        /** The longest prefix of [segments] that names one of [packages], or `null`. */
        private fun longestPackage(
            segments: List<String>,
            packages: Set<String>,
        ): List<String>? = (segments.size downTo 1).map(segments::take).firstOrNull { it.joinToString(".") in packages }

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
