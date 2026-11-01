package dev.phoneagent.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PrivilegedProtocolCheckTest {
    private val path = "dev/phoneagent/privileged/IPrivilegedCore.aidl"
    private val v1 =
        """
        package dev.phoneagent.privileged;
        // Frozen ids.
        interface IPrivilegedCore {
            int protocolVersion() = 1;
            /* [uid, pid] */
            int[] identity() = 2;
            boolean injectKey(int displayId, int keyCode) = 19;
        }
        """.trimIndent()
    private val fp1 = PrivilegedProtocolCheck.fingerprint(mapOf(path to v1))
    private val history1 = listOf(ProtocolEntry(1, fp1))

    @Test
    fun unchangedAidlWithItsRecordedVersionPasses() {
        assertNull(PrivilegedProtocolCheck.verify(history1, 1, fp1))
    }

    @Test
    fun changedAidlWithoutAVersionBumpFails() {
        val changed = v1.replace("int keyCode) = 19", "int keyCode, int metaState) = 19")
        val fp = PrivilegedProtocolCheck.fingerprint(mapOf(path to changed))
        val error = PrivilegedProtocolCheck.verify(history1, 1, fp)
        assertTrue(error.orEmpty().contains("Bump it to 2"), error)
        assertTrue(error.orEmpty().contains("`2 $fp`"), error)
    }

    @Test
    fun aBumpNeedsItsHistoryLine() {
        val error = PrivilegedProtocolCheck.verify(history1, 2, fp1)
        assertTrue(error.orEmpty().contains("append the line `2 $fp1`"), error)
        assertNull(
            PrivilegedProtocolCheck.verify(history1 + ProtocolEntry(2, fp1), 2, fp1),
            "behaviour-only bumps are allowed",
        )
    }

    @Test
    fun versionsCannotGoBackwards() {
        val history = history1 + ProtocolEntry(2, fp1)
        assertTrue(PrivilegedProtocolCheck.verify(history, 1, fp1).orEmpty().contains("lower than"))
        val unordered = listOf(ProtocolEntry(2, fp1), ProtocolEntry(1, fp1))
        assertTrue(PrivilegedProtocolCheck.verify(unordered, 1, fp1).orEmpty().contains("strictly increasing"))
    }

    @Test
    fun emptyHistoryFailsWithTheLineToAdd() {
        assertTrue(PrivilegedProtocolCheck.verify(emptyList(), 1, fp1).orEmpty().contains("`1 $fp1`"))
    }

    @Test
    fun commentsAndWhitespaceAreNotProtocol() {
        val reworded =
            v1
                .replace("// Frozen ids.", "// Ids 1-4 are frozen forever.")
                .replace("/* [uid, pid] */", "/*\n * [uid, pid] of the helper\n */")
                .replace("    ", "\t")
                .replace("\n", "\r\n")
        assertEquals(fp1, PrivilegedProtocolCheck.fingerprint(mapOf(path to reworded)))
    }

    private fun fp(aidl: String) = PrivilegedProtocolCheck.fingerprint(mapOf(path to aidl))

    @Test
    fun whitespaceBetweenTokensIsNotProtocol() {
        assertEquals(fp1, fp(v1.replace("int protocolVersion() = 1;", "int protocolVersion ()=1;")))
        assertEquals(fp1, fp(v1.replace("int[] identity() = 2;", "int [] identity( ) =2 ;")))
        assertEquals(fp1, fp(v1.replace("interface IPrivilegedCore {", "interface IPrivilegedCore{")))
        assertEquals(fp1, fp(v1.replace("(int displayId, int keyCode)", "( int displayId,int keyCode )")))
    }

    @Test
    fun stringConstantsAreProtocol() {
        val one =
            v1.replace(
                "    int protocolVersion() = 1;",
                "    const String URL = \"https://one\";\n    int protocolVersion() = 1;",
            )
        assertNotEquals(fp(one), fp(one.replace("https://one", "https://two")))
        assertNotEquals(fp(one), fp(one.replace("https://one", "https:  //one")), "spaces inside a string are text")
        val block = one.replace("https://one", "/* a */")
        assertNotEquals(fp(block), fp(one.replace("https://one", "/* b */")))
        val escaped = one.replace("https://one", "a\\\"//b")
        assertNotEquals(fp(escaped), fp(one.replace("https://one", "a\\\"//c")))
    }

    @Test
    fun unterminatedCommentsAndStringsFailLoudly() {
        assertThrows<IllegalArgumentException> { fp(v1.replace("/* [uid, pid] */", "/* [uid, pid]")) }
        val error = assertThrows<IllegalArgumentException> { fp(v1.replace("= 19;", "= 19; const String A = \"x;")) }
        assertTrue(error.message.orEmpty().contains("line 7"), error.message)
    }

    @Test
    fun renamingOrMovingAFileIsAProtocolChange() {
        assertNotEquals(fp1, PrivilegedProtocolCheck.fingerprint(mapOf("dev/other/IPrivilegedCore.aidl" to v1)))
        assertNotEquals(
            fp1,
            PrivilegedProtocolCheck.fingerprint(
                mapOf(
                    path to v1,
                    "dev/phoneagent/privileged/Extra.aidl" to "parcelable X;",
                ),
            ),
        )
    }

    @Test
    fun versionIsReadFromTheKotlinSource() {
        assertEquals(7, PrivilegedProtocolCheck.parseVersion("object P {\n    public const val VERSION: Int = 7\n}"))
        assertThrows<IllegalArgumentException> { PrivilegedProtocolCheck.parseVersion("object P") }
        assertThrows<IllegalArgumentException> {
            PrivilegedProtocolCheck.parseVersion("const val VERSION: Int = 1\nconst val VERSION: Int = 2")
        }
    }

    @Test
    fun historyParsingIgnoresCommentsAndRejectsMalformedLines() {
        val text = "# header\n\n1 $fp1  # first\n2 $fp1\n"
        assertEquals(listOf(ProtocolEntry(1, fp1), ProtocolEntry(2, fp1)), PrivilegedProtocolCheck.parseHistory(text))
        val error = assertThrows<IllegalArgumentException> { PrivilegedProtocolCheck.parseHistory("1 not-a-hash") }
        assertTrue(error.message.orEmpty().contains("line 1"))
        assertThrows<IllegalArgumentException> { PrivilegedProtocolCheck.parseHistory("01 $fp1") }
    }
}
