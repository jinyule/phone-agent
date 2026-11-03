package dev.phoneagent.app.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Only the packages that launch the helper (`dev.phoneagent.app.adb`) and check it on a device
 * (`dev.phoneagent.app.debug`), and their subpackages, may use the built-in adb client
 * `dev.phoneagent.core.adb`: it is setup code that must never be reachable from a tool or the agent
 * (AGENTS.md §3.4; core's PackageDependencyTest keeps core's side).
 *
 * References are imports (also wildcards and aliases) and fully qualified names in code, also split
 * across lines. Comments do not count; the text of string literals does (a known over-approximation).
 * A file without a package declaration is a violation: it cannot be placed.
 */
class CoreAdbBoundaryTest {
    @Test
    fun `only the launch and self-check packages use the adb client`() {
        val files =
            listOf("src/main/kotlin", "src/debug/kotlin", "src/release/kotlin")
                .map(::File)
                .filter { it.isDirectory }
                .flatMap { root ->
                    root
                        .walk()
                        .filter { it.extension == "kt" }
                        .map {
                            it
                                .relativeTo(
                                    root,
                                ).path to it.readText()
                        }.toList()
                }
        assertTrue("found only ${files.size} files", files.size >= 10)
        val adbFiles = files.count { (_, text) -> packageOf(codeOf(text)) == ADB_PACKAGE }
        assertTrue("the adb package is where this test expects it ($adbFiles files)", adbFiles >= 5)
        assertEquals(emptyList<String>(), violations(files))
    }

    @Test
    fun `each kind of reference outside those packages is caught`() {
        val ui = "package dev.phoneagent.app.ui\n"
        val files =
            listOf(
                "ui/Import.kt" to "${ui}import dev.phoneagent.core.adb.AdbClient\n",
                "ui/Wildcard.kt" to "${ui}import dev.phoneagent.core.adb.*\n",
                "ui/Alias.kt" to "${ui}import dev.phoneagent.core.adb.AdbClient as Client\n",
                "ui/Qualified.kt" to "${ui}val c = dev.phoneagent.core.adb.AdbCommand(\"id\")\n",
                "ui/Split.kt" to "${ui}val c =\n    dev.phoneagent.core\n        .adb\n        .AdbCommand(\"id\")\n",
                "ui/AfterComment.kt" to "$ui/* a */ import dev.phoneagent.core.adb.AdbClient // b\n",
                "adbx/Prefix.kt" to "package dev.phoneagent.app.adbx\nimport dev.phoneagent.core.adb.AdbClient\n",
                "tools/Tool.kt" to
                    "/* header */\npackage dev.phoneagent.app.tools\nimport dev.phoneagent.core.adb.PortSearchResult\n",
                "NoPackage.kt" to "import dev.phoneagent.core.adb.AdbClient\n",
                "adb/Allowed.kt" to "package dev.phoneagent.app.adb\nimport dev.phoneagent.core.adb.AdbClient\n",
                "adb/setup/Allowed.kt" to "package dev.phoneagent.app.adb.setup\nimport dev.phoneagent.core.adb.*\n",
                "debug/Allowed.kt" to
                    "package dev.phoneagent.app.debug\nval c = dev.phoneagent.core.adb.AdbCommand(\"id\")\n",
                "ui/Comments.kt" to
                    "$ui// dev.phoneagent.core.adb.AdbClient\n/* dev.phoneagent.core.adb\n */ val x = 1\n",
                "ui/OtherCore.kt" to
                    "${ui}import dev.phoneagent.core.adbx.Thing\nimport dev.phoneagent.core.model.Part\n",
                "NoPackageClean.kt" to "val x = 1\n",
            )
        assertEquals(
            listOf(
                "ui/Import.kt: dev.phoneagent.app.ui uses dev.phoneagent.core.adb",
                "ui/Wildcard.kt: dev.phoneagent.app.ui uses dev.phoneagent.core.adb",
                "ui/Alias.kt: dev.phoneagent.app.ui uses dev.phoneagent.core.adb",
                "ui/Qualified.kt: dev.phoneagent.app.ui uses dev.phoneagent.core.adb",
                "ui/Split.kt: dev.phoneagent.app.ui uses dev.phoneagent.core.adb",
                "ui/AfterComment.kt: dev.phoneagent.app.ui uses dev.phoneagent.core.adb",
                "adbx/Prefix.kt: dev.phoneagent.app.adbx uses dev.phoneagent.core.adb",
                "tools/Tool.kt: dev.phoneagent.app.tools uses dev.phoneagent.core.adb",
                "NoPackage.kt: no package declaration",
                "NoPackageClean.kt: no package declaration",
            ),
            violations(files),
        )
    }

    private fun violations(files: List<Pair<String, String>>): List<String> =
        files.mapNotNull { (path, text) ->
            val code = codeOf(text)
            val own = packageOf(code)
            when {
                own == null -> "$path: no package declaration"
                ALLOWED.any { own == it || own.startsWith("$it.") } -> null
                CORE_ADB.containsMatchIn(code) -> "$path: $own uses dev.phoneagent.core.adb"
                else -> null
            }
        }

    /** [text] without comments, and with the dots of qualified names joined across whitespace. */
    private fun codeOf(text: String): String = text.replace(COMMENT, " ").replace(DOT, ".")

    private fun packageOf(code: String): String? = PACKAGE.find(code)?.groupValues?.get(1)

    private companion object {
        const val ADB_PACKAGE = "dev.phoneagent.app.adb"
        val ALLOWED = listOf(ADB_PACKAGE, "dev.phoneagent.app.debug")
        val COMMENT = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")
        val DOT = Regex("""\s*\.\s*""")
        val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        val CORE_ADB = Regex("""\bdev\.phoneagent\.core\.adb\b(?!\w)""")
    }
}
