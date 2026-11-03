package dev.phoneagent.app.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HelperLaunchCommandTest {
    private val apk = "/data/app/~~a==/dev.phoneagent-b==/base.apk"

    @Test
    fun `the command is the one scripts start-helper sh runs, without the optional flags`() {
        val script = File("../scripts/start-helper.sh").readLines()
        val line = script.single { it.startsWith("remote=\"") }
        val remote =
            line
                .removePrefix("remote=\"")
                .removeSuffix("\"")
                .replace("\\\"", "\"")
                .replace("\\$", "$")
                .replace("\$apk", apk)
                .replace("\$name", "dev.phoneagent:helper")
                .replace("\$main_class", HelperLaunchCommand.MAIN_CLASS)
                .replace("\$package", "dev.phoneagent")
                .replace("\$extra", "")
        assertEquals(remote, HelperLaunchCommand.of(apk, "dev.phoneagent").text)
    }

    @Test
    fun `the command names the helper's real main class`() {
        assertEquals(HelperLaunchCommand.MAIN_CLASS, Class.forName(HelperLaunchCommand.MAIN_CLASS).name)
    }

    @Test
    fun `the nonce goes on stdin with a newline, never on the command line`() {
        val nonce = "0123456789abcdef".repeat(4)
        val command = HelperLaunchCommand.of(apk, "dev.phoneagent")
        assertFalse(command.text.contains(nonce))
        assertTrue(command.text.startsWith("read n; "))
        assertArrayEquals("$nonce\n".toByteArray(Charsets.US_ASCII), HelperLaunchCommand.stdin(nonce))
    }

    @Test
    fun `a quote in the APK path stays inside its word`() {
        val text = HelperLaunchCommand.of("/data/app/it's/base.apk", "dev.phoneagent").text
        assertTrue(text, text.contains("CLASSPATH='/data/app/it'\\''s/base.apk' setsid"))
    }
}
