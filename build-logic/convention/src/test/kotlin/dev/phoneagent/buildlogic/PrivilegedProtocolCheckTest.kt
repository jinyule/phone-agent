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

    private val core =
        """
        package dev.phoneagent.privileged;
        interface IPrivilegedCore {
            const int LIMIT = 3;
            int protocolVersion() = 1;
            int[] identity() = 2;
            void releaseAll() = 3;
            oneway void shutdown() = 4;
            boolean injectKey(int displayId, int keyCode) = 19;
        }
        """.trimIndent()

    private fun frozen(aidl: String) = PrivilegedProtocolCheck.checkFrozen(mapOf(path to aidl))

    @Test
    fun theFrozenTransactionsPassWhateverTheLayout() {
        assertNull(frozen(core))
        assertNull(
            frozen(core.replace("int protocolVersion() = 1;", "int protocolVersion ()=1;").replace("int[]", "int []")),
        )
    }

    @Test
    fun droppingOnewayFromShutdownFailsEvenWithABump() {
        val changed = core.replace("oneway void shutdown()", "void shutdown()")
        val error = frozen(changed).orEmpty()
        assertTrue(error.contains("4") && error.contains("oneway void shutdown ( ) = 4 ;"), error)
        val fp = PrivilegedProtocolCheck.fingerprint(mapOf(path to changed))
        assertNull(PrivilegedProtocolCheck.verify(listOf(ProtocolEntry(1, fp1), ProtocolEntry(2, fp)), 2, fp))
    }

    @Test
    fun anyChangeToTransactionsOneToFourFails() {
        val changes =
            listOf(
                "int[] identity() = 2;" to "Bundle identity() = 2;",
                "void releaseAll() = 3;" to "void releaseAll(int flags) = 3;",
                "void releaseAll() = 3;" to "",
                "oneway void shutdown() = 4;" to "oneway void shutdown() = 5;",
                "int protocolVersion() = 1;" to "int protocolVersion() = 1;\n    int other() = 1;",
            )
        for ((from, to) in changes) {
            assertTrue(frozen(core.replace(from, to)) != null, "$from -> $to")
        }
        assertTrue(frozen(core.replace("interface IPrivilegedCore", "interface IOther")) != null, "no interface")
        assertTrue(PrivilegedProtocolCheck.checkFrozen(mapOf("dev/other/I.aidl" to core)) != null, "file moved")
    }

    @Test
    fun anAnnotationOnTheInterfaceFailsEvenWithABump() {
        val annotations =
            listOf(
                "@Descriptor(value=\"evil.Other\") interface IPrivilegedCore",
                "@Descriptor(value = \"dev.phoneagent.privileged.IPrivilegedCore\") interface IPrivilegedCore",
                "@JavaDerive(toString=true) interface IPrivilegedCore",
            )
        for (annotated in annotations) {
            val changed = core.replace("interface IPrivilegedCore", annotated)
            val error = frozen(changed).orEmpty()
            assertTrue(error.contains("dev.phoneagent.privileged.IPrivilegedCore"), "$annotated: $error")
            val fp = PrivilegedProtocolCheck.fingerprint(mapOf(path to changed))
            assertNull(PrivilegedProtocolCheck.verify(listOf(ProtocolEntry(1, fp1), ProtocolEntry(2, fp)), 2, fp))
        }
        val imports =
            core.replace(
                "package dev.phoneagent.privileged;",
                "package dev.phoneagent.privileged;\nimport android.os.Bundle;",
            )
        assertNull(frozen(imports), "imports do not change the descriptor")
    }

    @Test
    fun anotherPackageFails() {
        val moved = core.replace("package dev.phoneagent.privileged;", "package dev.phoneagent.other;")
        assertTrue(frozen(moved).orEmpty().contains("dev.phoneagent.privileged.IPrivilegedCore"), frozen(moved))
    }

    @Test
    fun theHistoryOnlyGrowsAgainstTheBaseline() {
        val fp2 = "2".repeat(64)
        val history = listOf(ProtocolEntry(1, fp1), ProtocolEntry(2, fp2))
        assertNull(PrivilegedProtocolCheck.verifyAppendOnly(emptyList(), history), "no baseline file")
        assertNull(PrivilegedProtocolCheck.verifyAppendOnly(history.take(1), history), "appended")
        assertNull(PrivilegedProtocolCheck.verifyAppendOnly(history, history), "unchanged")
        val rewritten = listOf(ProtocolEntry(1, fp2))
        assertTrue(
            PrivilegedProtocolCheck.verifyAppendOnly(history.take(1), rewritten).orEmpty().contains("`1 $fp1`"),
            "rewritten in place",
        )
        assertTrue(PrivilegedProtocolCheck.verifyAppendOnly(history, history.drop(1)) != null, "first line removed")
        assertTrue(PrivilegedProtocolCheck.verifyAppendOnly(history, history.take(1)) != null, "last line removed")
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
