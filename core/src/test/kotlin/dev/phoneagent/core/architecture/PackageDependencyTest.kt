package dev.phoneagent.core.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
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
 *
 * References are imports (including wildcards) and fully qualified class names in code; comments and
 * string literals do not count.
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
                "agent/KnowsAdapter.kt: contract agent must not use capability model.openai",
                "model/openai/KnowsScreen.kt: capability model.openai must not use capability screen",
                "ports/KnowsSession.kt: contract ports (layer 1) must not use session (layer 4)",
                "tools/FullyQualified.kt: capability tools must not use capability policy.rules",
            ),
            violations(scope, root),
        )
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
                val own = file.packagee?.name?.let(Unit::of) ?: return@flatMap emptyList()
                val imports = file.imports.map { packageOfImport(it.name, it.isWildcard) }
                val referenced = imports + fullyQualifiedPackages(file.text)
                referenced
                    .mapNotNull(Unit::of)
                    .distinct()
                    .mapNotNull { problem(own, it) }
                    .map { "${file.path.substringAfter(root)}: $it" }
            }.sorted()

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
            used is Unit.Capability && used != own -> "capability ${own.id} must not use capability ${used.id}"
            else -> null
        }

    private val Unit.Contract.label: String get() = name.ifEmpty { "core" }

    private fun layer(contract: Unit.Contract): Int =
        if (contract.name.isEmpty()) 0 else CONTRACT_LAYERS.getValue(contract.name)

    private companion object {
        const val BASE = "dev.phoneagent.core"

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

        val COMMENT_OR_STRING = Regex("""//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\\n])*"""")
        val QUALIFIED = Regex("""\b(dev\.phoneagent\.core(?:\.[a-z][a-z0-9_]*)*)\.[A-Z]""")
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

        /** Packages of fully qualified class references in code, also when formatted across lines. */
        fun fullyQualifiedPackages(text: String): List<String> {
            val code = AROUND_DOT.replace(HEADER_LINE.replace(COMMENT_OR_STRING.replace(text, ""), ""), ".")
            return QUALIFIED.findAll(code).map { it.groupValues[1] }.toList()
        }
    }
}
