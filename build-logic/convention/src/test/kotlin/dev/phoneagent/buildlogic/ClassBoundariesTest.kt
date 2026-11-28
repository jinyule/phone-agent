package dev.phoneagent.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

class ClassBoundariesTest {
    @TempDir
    lateinit var dir: File

    private val core =
        ClassBoundary(
            "dev.phoneagent.core.adb",
            listOf("dev.phoneagent.app.adb"),
            listOf("dev.phoneagent.app.debug.Check"),
        )

    /** A user of [core] from an allowed place, so the boundary is not reported as unreferenced. */
    private val allowedUser = compiled("dev/phoneagent/app/adb/Launcher", "Ldev/phoneagent/core/adb/AdbClient;")

    @Test
    fun `a class outside the allowed places that names the package is a violation, in either form`() {
        val classes =
            listOf(
                allowedUser,
                compiled("dev/phoneagent/app/ui/Descriptor", "(Ldev/phoneagent/core/adb/AdbClient;)V"),
                compiled("dev/phoneagent/app/ui/ClassConstant", "dev/phoneagent/core/adb/AdbCommand"),
                compiled("dev/phoneagent/app/ui/Reflection", "dev.phoneagent.core.adb.AdbClient"),
                compiled("dev/phoneagent/app/ui/Inner\$1", "dev/phoneagent/core/adb/sub/Thing"),
                compiled("Root", "dev/phoneagent/core/adb/AdbClient"),
            )
        assertEquals(
            listOf(
                "Root references dev.phoneagent.core.adb",
                "dev.phoneagent.app.ui.ClassConstant references dev.phoneagent.core.adb",
                "dev.phoneagent.app.ui.Descriptor references dev.phoneagent.core.adb",
                "dev.phoneagent.app.ui.Inner\$1 references dev.phoneagent.core.adb",
                "dev.phoneagent.app.ui.Reflection references dev.phoneagent.core.adb",
            ),
            ClassBoundaries.violations(classes, listOf(core)),
        )
    }

    @Test
    fun `allowed packages and classes pass with their subpackages and nested classes, as does the package itself`() {
        val classes =
            listOf(
                allowedUser,
                compiled("dev/phoneagent/app/adb/setup/Wizard", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/app/debug/Check", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/app/debug/Check\$Scenarios\$run\$1", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/core/adb/AdbClient", "dev/phoneagent/core/adb/AdbSession"),
                compiled("dev/phoneagent/core/adb/tls/Exporter", "dev/phoneagent/core/adb/AdbClient"),
            )
        assertEquals(emptyList<String>(), ClassBoundaries.violations(classes, listOf(core)))
    }

    @Test
    fun `names that only start like an allowed one are not allowed, and look-alike packages are not references`() {
        val classes =
            listOf(
                allowedUser,
                compiled("dev/phoneagent/app/adbx/Prefix", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/app/debug/CheckHelper", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/app/debug/sub/Check", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/core/adbx/Other", "dev/phoneagent/core/adb/AdbClient"),
                compiled(
                    "dev/phoneagent/app/ui/LookAlike",
                    "dev/phoneagent/core/adbx/Thing",
                    "dev.phoneagent.core.adbx.Thing",
                ),
                compiled("dev/phoneagent/app/ui/Plain", "dev/phoneagent/core/model/Part"),
            )
        assertEquals(
            listOf(
                "dev.phoneagent.app.adbx.Prefix references dev.phoneagent.core.adb",
                "dev.phoneagent.app.debug.CheckHelper references dev.phoneagent.core.adb",
                "dev.phoneagent.app.debug.sub.Check references dev.phoneagent.core.adb",
                "dev.phoneagent.core.adbx.Other references dev.phoneagent.core.adb",
            ),
            ClassBoundaries.violations(classes, listOf(core)),
        )
    }

    @Test
    fun `a boundary that no class references is reported, since the scan probably read nothing`() {
        assertEquals(
            listOf("no class references dev.phoneagent.core.adb; were the classes read?"),
            ClassBoundaries.violations(
                listOf(compiled("dev/phoneagent/app/ui/Plain", "java/lang/String")),
                listOf(core),
            ),
        )
        assertEquals(
            listOf("no class references dev.phoneagent.core.adb; were the classes read?"),
            ClassBoundaries.violations(emptyList(), listOf(core)),
        )
    }

    @Test
    fun `in the app only the launch package and the self-check use the client, and the application starts launches`() {
        val classes =
            listOf(
                compiled("dev/phoneagent/app/adb/BuiltinAdb", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/app/PhoneAgentApplication", "dev/phoneagent/app/adb/HelperAutoLaunch"),
                compiled(
                    "dev/phoneagent/app/debug/AdbSelfCheck\$Scenarios",
                    "dev/phoneagent/app/adb/BuiltinAdb",
                    "dev/phoneagent/core/adb/AdbClient",
                ),
                compiled(
                    "dev/phoneagent/app/debug/AdbSelfCheckKt",
                    "dev/phoneagent/app/adb/LaunchConfig",
                    "dev/phoneagent/core/adb/PairingCode",
                ),
                compiled("dev/phoneagent/app/AppUsesClient", "dev/phoneagent/core/adb/AdbClient"),
                compiled("dev/phoneagent/app/MainActivity", "dev/phoneagent/app/adb/HelperAutoLaunch"),
                compiled("dev/phoneagent/app/debug/HelperSelfCheck", "dev/phoneagent/app/adb/BuiltinAdb"),
                compiled("dev/phoneagent/app/debug/AdbSelfCheckReceiver", "dev/phoneagent/app/adb/BuiltinAdb"),
                compiled("dev/phoneagent/app/tools/Relay", "dev/phoneagent/app/adb/BuiltinAdb"),
            )
        assertEquals(
            listOf(
                "dev.phoneagent.app.AppUsesClient references dev.phoneagent.core.adb",
                "dev.phoneagent.app.MainActivity references dev.phoneagent.app.adb",
                "dev.phoneagent.app.debug.AdbSelfCheckReceiver references dev.phoneagent.app.adb",
                "dev.phoneagent.app.debug.HelperSelfCheck references dev.phoneagent.app.adb",
                "dev.phoneagent.app.tools.Relay references dev.phoneagent.app.adb",
            ),
            ClassBoundaries.violations(classes, APP_CLASS_BOUNDARIES),
        )
    }

    @Test
    fun `in javac output a direct use, a relay through the launch package and a constant are caught`() {
        val sources =
            mapOf(
                "dev/phoneagent/core/adb/AdbClient.java" to
                    "package dev.phoneagent.core.adb;\npublic class AdbClient {\n" +
                    "    public static final int PORT = 5555;\n    public void connect() {}\n}\n",
                "dev/phoneagent/app/adb/BuiltinAdb.java" to
                    "package dev.phoneagent.app.adb;\npublic class BuiltinAdb {\n" +
                    "    public dev.phoneagent.core.adb.AdbClient client() {\n" +
                    "        return new dev.phoneagent.core.adb.AdbClient();\n    }\n}\n",
                "dev/phoneagent/app/PhoneAgentApplication.java" to
                    "package dev.phoneagent.app;\npublic class PhoneAgentApplication {\n" +
                    "    Object adb = new dev.phoneagent.app.adb.BuiltinAdb();\n}\n",
                "dev/phoneagent/app/ui/Direct.java" to
                    "package dev.phoneagent.app.ui;\nimport dev.phoneagent.core.adb.AdbClient;\n" +
                    "class Direct { void run(AdbClient c) { c.connect(); } }\n",
                "dev/phoneagent/app/ui/Relay.java" to
                    "package dev.phoneagent.app.ui;\n" +
                    "class Relay { void run(dev.phoneagent.app.adb.BuiltinAdb adb) { adb.client().connect(); } }\n",
                "dev/phoneagent/app/ui/Constant.java" to
                    "package dev.phoneagent.app.ui;\n" +
                    "class Constant { int port = dev.phoneagent.core.adb.AdbClient.PORT; }\n",
            )
        val out = javac(sources)
        val classes = ClassBoundaries.read(listOf(out), emptyList())
        assertEquals(
            listOf(
                // javac copies the constant's value but keeps its class in the constant pool.
                "dev.phoneagent.app.ui.Constant references dev.phoneagent.core.adb",
                "dev.phoneagent.app.ui.Direct references dev.phoneagent.core.adb",
                "dev.phoneagent.app.ui.Relay references dev.phoneagent.app.adb",
                "dev.phoneagent.app.ui.Relay references dev.phoneagent.core.adb",
            ),
            ClassBoundaries.violations(classes, APP_CLASS_BOUNDARIES),
        )
    }

    @Test
    fun `classes are read from directories and jars, named by their path, and a missing directory is skipped`() {
        val root = File(dir, "classes")
        File(root, "dev/phoneagent/app/ui").mkdirs()
        File(root, "dev/phoneagent/app/ui/A.class").writeBytes(byteArrayOf(1))
        File(root, "dev/phoneagent/app/ui/notes.txt").writeText("not a class")
        val jar = File(dir, "R.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("dev/phoneagent/app/R\$string.class"))
            zip.write(byteArrayOf(2))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.closeEntry()
        }
        val classes = ClassBoundaries.read(listOf(root, File(dir, "absent")), listOf(jar))
        assertEquals(listOf("dev/phoneagent/app/ui/A", "dev/phoneagent/app/R\$string"), classes.map { it.name })
        assertEquals(listOf(1.toByte(), 2.toByte()), classes.map { it.bytes.single() })
    }

    @Test
    fun `boundary names must be dotted names`() {
        for (bad in listOf("dev/phoneagent/core/adb", "dev.phoneagent.", "", "dev..adb")) {
            assertThrows<IllegalArgumentException>(bad) { ClassBoundary(bad, emptyList(), emptyList()) }
            assertThrows<IllegalArgumentException>(bad) { ClassBoundary("a.b", listOf(bad), emptyList()) }
            assertThrows<IllegalArgumentException>(bad) { ClassBoundary("a.b", emptyList(), listOf(bad)) }
        }
        assertTrue(ClassBoundary("a.b", listOf("c"), listOf("d.E\$F")).allows("d/E\$F\$1"))
        assertFalse(ClassBoundary("a.b", listOf("c"), emptyList()).allows("Top"))
    }

    private fun javac(sources: Map<String, String>): File {
        val src = File(dir, "src")
        val files =
            sources.map { (path, text) ->
                File(src, path).apply {
                    parentFile.mkdirs()
                    writeText(text)
                }
            }
        val out = File(dir, "out").apply { mkdirs() }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "the tests run on a JDK" }
        val status = compiler.run(null, null, null, "-d", out.path, *files.map { it.path }.toTypedArray())
        assertEquals(0, status, "javac")
        // The client itself is core's, not the app's: only the app's classes are scanned.
        File(out, "dev/phoneagent/core").deleteRecursively()
        return out
    }
}

/** A minimal valid class file [name] (internal form) whose constant pool also holds [strings]. */
private fun compiled(
    name: String,
    vararg strings: String,
): CompiledClass {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeInt(0xCAFEBABE.toInt())
        out.writeShort(0)
        out.writeShort(JAVA_17)
        val utf8 = listOf(name, "java/lang/Object") + strings
        // Entries: name, Class(name), Object, Class(Object), then the strings.
        out.writeShort(utf8.size + 2 + 1)
        out.utf8(name)
        out.classRef(1)
        out.utf8("java/lang/Object")
        out.classRef(3)
        for (text in strings) out.utf8(text)
        out.writeShort(ACC_PUBLIC_SUPER)
        out.writeShort(2)
        out.writeShort(4)
        repeat(4) { out.writeShort(0) }
    }
    return CompiledClass(name, bytes.toByteArray())
}

private fun DataOutputStream.utf8(text: String) {
    writeByte(1)
    writeUTF(text)
}

private fun DataOutputStream.classRef(index: Int) {
    writeByte(7)
    writeShort(index)
}

private const val JAVA_17 = 61
private const val ACC_PUBLIC_SUPER = 0x21
