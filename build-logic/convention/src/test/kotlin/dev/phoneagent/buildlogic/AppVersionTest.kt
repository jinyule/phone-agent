package dev.phoneagent.buildlogic

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AppVersionTest {
    @Test
    fun `computes the documented formula`() {
        assertEquals(10_001, AppVersion.parse("0.1.0-alpha.1").versionCode)
        assertEquals(1_020_335, AppVersion.parse("1.2.3-beta.5").versionCode)
        assertEquals(1_020_360, AppVersion.parse("1.2.3-rc.0").versionCode)
        assertEquals(1_020_399, AppVersion.parse("1.2.3").versionCode)
    }

    @Test
    fun `later releases always get larger codes`() {
        val ordered =
            listOf(
                "0.1.0-alpha.1",
                "0.1.0-alpha.29",
                "0.1.0-beta.0",
                "0.1.0-beta.29",
                "0.1.0-rc.0",
                "0.1.0-rc.29",
                "0.1.0",
                "0.1.1-alpha.0",
                "0.1.99",
                "0.2.0-alpha.0",
                "0.99.99",
                "1.0.0-alpha.0",
                "1.0.0",
            )
        val codes = ordered.map { AppVersion.parse(it).versionCode }
        codes.zipWithNext().forEach { (a, b) -> assertTrue(a < b, "$a should be < $b in $ordered") }
    }

    @Test
    fun `round-trips the version name`() {
        listOf("0.1.0-alpha.1", "0.3.0-rc.2", "2.0.0").forEach {
            assertEquals(it, AppVersion.parse(it).versionName)
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "1.0", "1.0.0.0", "v1.0.0", "01.0.0", "1.0.0-beta", "1.0.0-dev.1", "1.0.0-alpha.01",
            "1.0.0-alpha.30", "1.0.0-rc.30", "1.100.0", "1.0.100", "2100.0.0", "0.0.0-alpha.0",
        ],
    )
    fun `rejects malformed or out-of-range names`(name: String) {
        val error = assertThrows<IllegalArgumentException> { AppVersion.parse(name) }
        assertTrue(error.message.orEmpty().contains("'$name'"), "message should name the value: ${error.message}")
    }
}
