package dev.phoneagent.core.model.openai

import dev.phoneagent.core.model.ChatModel
import dev.phoneagent.core.model.ProviderProfile
import dev.phoneagent.core.model.openai.Kit.base
import dev.phoneagent.core.model.openai.LeakedCallFormat.DSML
import dev.phoneagent.core.model.openai.LeakedCallFormat.TOOL_CALL_TAG
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockWebServer
import mockwebserver3.junit5.StartStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * Which leaked-call formats a route may recover is checked when a model is created and when
 * "test and save" starts (model-layer §3.1): a preset route, its preset found as
 * [WireOptions.defaultsFor] finds it (official hosts included), only its preset's
 * [ProviderPreset.leakedCallFormats]; a custom route only what it declares in
 * [ChatQuirks.leakedCallFormats], which it starts without.
 */
class LeakedCallRouteTest {
    @StartStop
    private val server = MockWebServer()

    private val adapters = Kit.adapters()

    private fun options(
        profile: ProviderProfile,
        recover: Set<LeakedCallFormat>,
        declared: Set<LeakedCallFormat> = emptySet(),
    ): WireOptions {
        val defaults = WireOptions.defaultsFor(profile)
        return defaults.copy(chat = defaults.chat.copy(leakedCallFormats = declared, recoverLeakedCalls = recover))
    }

    private fun create(
        profile: ProviderProfile,
        recover: Set<LeakedCallFormat>,
        declared: Set<LeakedCallFormat> = emptySet(),
    ): ChatModel =
        adapters.model(profile, Kit.MODEL, Kit.capabilities(), Kit.blobs(), options(profile, recover, declared))

    private fun assertRefused(
        profile: ProviderProfile,
        recover: Set<LeakedCallFormat>,
        declared: Set<LeakedCallFormat> = emptySet(),
    ) {
        val message = assertThrows<IllegalArgumentException> { create(profile, recover, declared) }.message.orEmpty()
        assertTrue(message.startsWith("Provider ${profile.id}:"), message)
    }

    @Test
    fun `a preset route recovers only its preset's formats`() {
        val deepseek = Kit.profile(BASE, preset = "deepseek")
        assertDoesNotThrow { create(deepseek, setOf(DSML)) }
        assertRefused(deepseek, setOf(TOOL_CALL_TAG))
        assertRefused(deepseek, setOf(DSML, TOOL_CALL_TAG))
        assertDoesNotThrow { create(Kit.profile(BASE, preset = "ollama"), setOf(TOOL_CALL_TAG)) }
        assertRefused(Kit.profile(BASE, preset = "kimi"), setOf(TOOL_CALL_TAG))
        assertRefused(Kit.profile(BASE, preset = "openai"), setOf(DSML))
    }

    @Test
    fun `a custom route at an official host is checked as its preset`() {
        val official = Kit.profile("https://API.deepseek.com/v1")
        assertDoesNotThrow { create(official, setOf(DSML)) }
        assertRefused(official, setOf(TOOL_CALL_TAG))
        assertRefused(official, setOf(TOOL_CALL_TAG), declared = setOf(TOOL_CALL_TAG))
    }

    @Test
    fun `a custom route recovers only what it declares, and declares nothing by default`() {
        val custom = Kit.profile(BASE)
        assertEquals(emptySet<LeakedCallFormat>(), WireOptions.defaultsFor(custom).chat.leakedCallFormats)
        assertRefused(custom, setOf(TOOL_CALL_TAG))
        assertDoesNotThrow { create(custom, setOf(TOOL_CALL_TAG), declared = setOf(TOOL_CALL_TAG)) }
        assertDoesNotThrow { create(custom, emptySet(), declared = setOf(TOOL_CALL_TAG, DSML)) }
        assertRefused(custom, setOf(DSML, TOOL_CALL_TAG), declared = setOf(TOOL_CALL_TAG))
        // An unknown preset id resolves as defaultsFor resolves it: a custom route.
        assertDoesNotThrow { create(Kit.profile(BASE, preset = "gone"), setOf(DSML), declared = setOf(DSML)) }
    }

    @Test
    fun `a preset route takes its formats from its preset, never from a declaration`() {
        assertRefused(Kit.profile(BASE, preset = "deepseek"), emptySet(), declared = setOf(DSML))
        assertRefused(Kit.profile("https://api.deepseek.com"), emptySet(), declared = setOf(DSML))
    }

    @Test
    fun `what a route allows is all settings may offer`() {
        val deepseek = Kit.profile(BASE, preset = "deepseek")
        assertEquals(setOf(DSML), WireOptions.defaultsFor(deepseek).allowedLeakedCalls(deepseek))
        val official = Kit.profile("https://dashscope.aliyuncs.com/compatible-mode/v1")
        assertEquals(setOf(TOOL_CALL_TAG), WireOptions.defaultsFor(official).allowedLeakedCalls(official))
        val custom = Kit.profile(BASE)
        assertEquals(emptySet<LeakedCallFormat>(), WireOptions.defaultsFor(custom).allowedLeakedCalls(custom))
        assertEquals(setOf(DSML), options(custom, emptySet(), declared = setOf(DSML)).allowedLeakedCalls(custom))
    }

    @Test
    fun `test and save refuses such options before any request`() =
        runTest {
            val custom = Kit.profile(server.base())
            assertThrows<IllegalArgumentException> {
                adapters.probe().testAndSave(custom, Kit.MODEL, Kit.capabilities(), wire = options(custom, setOf(DSML)))
            }
            assertEquals(0, server.requestCount)
        }

    private companion object {
        const val BASE = "https://gateway.example/v1"
    }
}
