package dev.phoneagent.buildlogic

import java.io.File
import java.io.Serializable
import java.util.zip.ZipFile

/**
 * Who may reference [target] (a dotted package name) in compiled classes: the classes of
 * [packages] and their subpackages, the classes in [classes] with their nested and synthetic (`$`)
 * classes, and the classes of [target] and its subpackages themselves. Every other class that
 * references it is a violation.
 *
 * Checked on bytes, not on sources: every name a class uses is a constant-pool UTF-8 entry, so a
 * class references the package when its bytes contain it in internal form (`a/b/c/`) or dotted
 * (`a.b.c.`, as a string constant for reflection would). That covers imports, aliases, qualified
 * names, Java sources, Kotlin under `src/main/java`, typealiases (their users name the expanded
 * type), inline functions (their body is copied into the caller) and values handed on by an
 * allowed class (the caller names their type). Comments are not compiled and do not count. A
 * compile-time constant is copied: javac still keeps its class in the constant pool, Kotlin may
 * not (a constant carries no capability). Any other text that contains the dotted name counts (a
 * known over-approximation).
 */
data class ClassBoundary(
    val target: String,
    val packages: List<String>,
    val classes: List<String>,
) : Serializable {
    init {
        for (name in listOf(target) + packages + classes) {
            require(DOTTED_NAME.matches(name)) { "not a dotted name: '$name'" }
        }
    }

    /** Whether the class [name] (internal form, `a/b/C$D`) may reference [target]. */
    fun allows(name: String): Boolean {
        val own = name.substringBeforeLast('/', "")
        val inPackage = (packages + target).map(::internal).any { own == it || own.startsWith("$it/") }
        return inPackage || classes.map(::internal).any { name == it || name.startsWith("$it$") }
    }

    /** Whether the class file [bytes] name [target]. */
    fun referencedBy(bytes: ByteArray): Boolean {
        // ISO-8859-1 turns each byte into one char, and the names are ASCII.
        val text = String(bytes, Charsets.ISO_8859_1)
        return "${internal(target)}/" in text || "$target." in text
    }
}

private val DOTTED_NAME = Regex("""[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*""")

private fun internal(dotted: String): String = dotted.replace('.', '/')

/** One compiled class: its [name] in internal form (`a/b/C$D`) and its [bytes]. */
class CompiledClass(
    val name: String,
    val bytes: ByteArray,
)

object ClassBoundaries {
    /**
     * The violations of [boundaries] among [classes], one line each, sorted. A boundary that no
     * class references at all is one too: each has users in every variant, so the scan most likely
     * missed the compiled output.
     */
    fun violations(
        classes: List<CompiledClass>,
        boundaries: List<ClassBoundary>,
    ): List<String> {
        val found = mutableListOf<String>()
        for (boundary in boundaries) {
            val users = classes.filter { boundary.referencedBy(it.bytes) }
            if (users.isEmpty()) found += "no class references ${boundary.target}; were the classes read?"
            for (user in users) {
                if (!boundary.allows(user.name)) found += "${user.name.replace('/', '.')} references ${boundary.target}"
            }
        }
        return found.sorted()
    }

    /** The `.class` files under [dirs] and in [jars], each named by its path. */
    fun read(
        dirs: List<File>,
        jars: List<File>,
    ): List<CompiledClass> {
        val fromDirs =
            dirs.filter { it.isDirectory }.flatMap { root ->
                root
                    .walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map {
                        CompiledClass(
                            it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class"),
                            it.readBytes(),
                        )
                    }.toList()
            }
        val fromJars =
            jars.flatMap { jar ->
                ZipFile(jar).use { zip ->
                    zip
                        .entries()
                        .asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .map { entry ->
                            CompiledClass(
                                entry.name.removeSuffix(".class"),
                                zip.getInputStream(entry).use { it.readBytes() },
                            )
                        }.toList()
                }
            }
        return fromDirs + fromJars
    }
}
