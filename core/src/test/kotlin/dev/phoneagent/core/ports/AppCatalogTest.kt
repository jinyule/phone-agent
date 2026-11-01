package dev.phoneagent.core.ports

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AppCatalogTest {
    private val wechat = AppEntry("com.tencent.mm", "微信", launchable = true)

    @Test
    fun `entries have a valid package name and a label`() {
        assertEquals(
            """{"packageName":"com.tencent.mm","label":"微信","launchable":true}""",
            Json.encodeToString(wechat),
        )
        assertEquals(wechat, Json.decodeFromString<AppEntry>(Json.encodeToString(wechat)))
        assertThrows<IllegalArgumentException> { AppEntry("wechat", "微信", true) }
        assertThrows<IllegalArgumentException> { AppEntry("com.tencent.mm", " ", true) }
        assertThrows<IllegalArgumentException> {
            Json.decodeFromString<AppEntry>("""{"packageName":"mm","label":"微信","launchable":true}""")
        }
    }

    @Test
    fun `a catalog lists installed apps, launchable or not`() =
        runTest {
            val service = AppEntry("com.example.push", "推送服务", launchable = false)
            val catalog =
                object : AppCatalog {
                    override suspend fun apps() = listOf(wechat, service)
                }
            assertEquals(listOf("com.tencent.mm"), catalog.apps().filter { it.launchable }.map { it.packageName })
        }
}
