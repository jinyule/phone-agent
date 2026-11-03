package dev.phoneagent.app.model.settings

import dev.phoneagent.app.model.settings.SettingsFixtures.DEEPSEEK
import dev.phoneagent.app.model.settings.SettingsFixtures.MAIN
import dev.phoneagent.app.model.settings.SettingsFixtures.REF
import dev.phoneagent.app.model.settings.SettingsFixtures.SEEING
import dev.phoneagent.app.model.settings.SettingsFixtures.SMALL
import dev.phoneagent.app.model.settings.SettingsFixtures.profile
import dev.phoneagent.app.model.settings.SettingsFixtures.provider
import dev.phoneagent.app.model.settings.SettingsFixtures.settings
import dev.phoneagent.app.model.settings.SettingsFixtures.verified
import dev.phoneagent.core.ids.ModelId
import dev.phoneagent.core.ids.ProviderId
import dev.phoneagent.core.model.ModelProtocol
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.openai.ChatQuirks
import dev.phoneagent.core.model.openai.LeakedCallFormat
import dev.phoneagent.core.model.openai.WireOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Load-time validation of `model-settings.json`: each rule has a failing case. */
class ModelSettingsTest {
    private val json = Json { encodeDefaults = true }

    private fun text(settings: ModelSettings) = json.encodeToString(serializer<ModelSettings>(), settings)

    private fun problemOf(settings: ModelSettings): SettingsProblem? =
        (ModelSettingsFile.parse(text(settings)) as? SettingsLoad.Broken)?.problem

    @Test
    fun validSettingsRoundTripWithTheirOptionsAndRoles() {
        val wire = WireOptions(chat = ChatQuirks(parallelToolCalls = false, streamUsage = false))
        val settings =
            settings(
                providers = listOf(provider(wire = wire)),
                roles =
                    mapOf(
                        ModelRole.MAIN to RoleBinding(DEEPSEEK, MAIN),
                        ModelRole.LIGHT to RoleBinding(DEEPSEEK, SMALL),
                        ModelRole.VISION to RoleBinding(DEEPSEEK, SEEING),
                    ),
            )
        assertEquals(SettingsLoad.Loaded(settings), ModelSettingsFile.parse(text(settings)))
        assertEquals(setOf(REF), settings.refs())
        val stored = Json.parseToJsonElement(text(settings)).jsonObject
        assertEquals(JsonPrimitive(1), stored["version"])
        assertTrue((stored["roles"] as JsonObject).keys == setOf("MAIN", "LIGHT", "VISION"))
    }

    @Test
    fun lightAndVisionFollowMainUnlessTheyHaveTheirOwn() {
        val main = settings()
        assertEquals(RoleBinding(DEEPSEEK, MAIN), main.binding(ModelRole.LIGHT))
        assertEquals(RoleBinding(DEEPSEEK, MAIN), main.binding(ModelRole.VISION))
        val own = settings(roles = main.roles + (ModelRole.LIGHT to RoleBinding(DEEPSEEK, SMALL)))
        assertEquals(RoleBinding(DEEPSEEK, SMALL), own.binding(ModelRole.LIGHT))
    }

    @Test
    fun anotherVersionIsRefused() {
        assertEquals(
            SettingsProblem(SettingsProblemKind.UNSUPPORTED_VERSION, "version"),
            problemOf(settings().copy(version = 2)),
        )
    }

    @Test
    fun duplicateProvidersAndModelsAreRefused() {
        assertEquals(
            SettingsProblemKind.DUPLICATE_PROVIDER,
            problemOf(settings(providers = listOf(provider(), provider())))?.kind,
        )
        val twice = provider(models = listOf(StoredModel(MAIN, verified), StoredModel(MAIN, verified)))
        assertEquals(
            SettingsProblem(SettingsProblemKind.DUPLICATE_MODEL, "providers.deepseek"),
            problemOf(settings(providers = listOf(twice))),
        )
    }

    @Test
    fun aProtocolWithoutAnAdapterIsRefused() {
        val anthropic = SettingsFixtures.local.copy(protocol = ModelProtocol.ANTHROPIC_MESSAGES)
        val stored = provider(profile = anthropic, wire = WireOptions())
        val settings = settings(listOf(stored), mapOf(ModelRole.MAIN to RoleBinding(anthropic.id, MAIN)))
        assertEquals(SettingsProblemKind.UNSUPPORTED_PROTOCOL, problemOf(settings)?.kind)
    }

    @Test
    fun anUnknownPresetIsRefused() {
        val unknown = profile.copy(preset = "no-such-preset")
        assertEquals(SettingsProblemKind.UNKNOWN_PRESET, problemOf(settings(listOf(provider(profile = unknown))))?.kind)
    }

    @Test
    fun optionsThatDoNotFitTheRouteAreRefused() {
        // A preset route declares its formats itself; recovering one it does not allow is refused too.
        val declares = WireOptions(chat = ChatQuirks(leakedCallFormats = setOf(LeakedCallFormat.DSML)))
        val recovers = WireOptions(chat = ChatQuirks(recoverLeakedCalls = setOf(LeakedCallFormat.TOOL_CALL_TAG)))
        for (wire in listOf(declares, recovers)) {
            assertEquals(
                SettingsProblem(SettingsProblemKind.OPTIONS_DO_NOT_FIT, "providers.deepseek"),
                problemOf(settings(listOf(provider(wire = wire)))),
            )
        }
        val allowed =
            WireOptions(chat = ChatQuirks(parallelToolCalls = false, recoverLeakedCalls = setOf(LeakedCallFormat.DSML)))
        assertNull(problemOf(settings(listOf(provider(wire = allowed)))))
    }

    @Test
    fun theMainRoleIsRequiredAndMustNameAConfiguredModel() {
        assertEquals(
            SettingsProblem(SettingsProblemKind.NO_MAIN_MODEL, "roles"),
            problemOf(settings(roles = emptyMap())),
        )
        assertEquals(
            SettingsProblem(SettingsProblemKind.UNKNOWN_MODEL, "roles.MAIN"),
            problemOf(settings(roles = mapOf(ModelRole.MAIN to RoleBinding(DEEPSEEK, ModelId("gone"))))),
        )
        assertEquals(
            SettingsProblem(SettingsProblemKind.UNKNOWN_MODEL, "roles.LIGHT"),
            problemOf(
                settings(
                    roles =
                        mapOf(
                            ModelRole.MAIN to RoleBinding(DEEPSEEK, MAIN),
                            ModelRole.LIGHT to RoleBinding(ProviderId("elsewhere"), SMALL),
                        ),
                ),
            ),
        )
    }

    @Test
    fun theMainModelMustHaveCalledATool() {
        for (capabilities in listOf(verified.copy(tools = false), verified.copy(verified = emptySet()))) {
            val stored = provider(models = listOf(StoredModel(MAIN, capabilities)))
            assertEquals(
                SettingsProblem(SettingsProblemKind.MAIN_WITHOUT_TOOLS, "roles.MAIN"),
                problemOf(settings(listOf(stored))),
            )
        }
    }

    @Test
    fun theVisionModelMustAcceptImages() {
        val roles =
            mapOf(
                ModelRole.MAIN to RoleBinding(DEEPSEEK, MAIN),
                ModelRole.VISION to RoleBinding(DEEPSEEK, SMALL),
            )
        assertEquals(
            SettingsProblem(SettingsProblemKind.VISION_WITHOUT_IMAGES, "roles.VISION"),
            problemOf(settings(roles = roles)),
        )
    }

    @Test
    fun brokenJsonKeepsOnlyThePathAndOffsetNotTheInput() {
        val secretLooking = "sk-test-not-a-real-key"
        val wrongType = text(settings()).replace("\"version\":1", "\"version\":\"$secretLooking\"")
        val broken = ModelSettingsFile.parse(wrongType) as SettingsLoad.Broken
        assertEquals(SettingsProblemKind.MALFORMED, broken.problem.kind)
        assertFalse(broken.problem.toString().contains(secretLooking))
        assertTrue(broken.problem.where!!.contains("offset"))
        val notJson = ModelSettingsFile.parse("{\"providers\": [ $secretLooking") as SettingsLoad.Broken
        assertEquals(SettingsProblemKind.MALFORMED, notJson.problem.kind)
        assertFalse(notJson.problem.toString().contains(secretLooking))
        val missing = ModelSettingsFile.parse("{\"version\":1,\"providers\":[]}") as SettingsLoad.Broken
        assertEquals(SettingsProblemKind.MALFORMED, missing.problem.kind)
        val unknownKey =
            ModelSettingsFile.parse(
                text(settings()).replaceFirst("{", "{\"$secretLooking\":1,"),
            ) as SettingsLoad.Broken
        assertEquals(SettingsProblemKind.MALFORMED, unknownKey.problem.kind)
        assertFalse(unknownKey.problem.toString().contains(secretLooking))
    }

    @Test
    fun aValueThatFailsItsOwnValidationIsRefusedWithoutItsMessage() {
        // Plain http to a public host: the profile refuses it while decoding.
        val public = text(settings()).replace("https://api.deepseek.com", "http://api.deepseek.com")
        val problem = (ModelSettingsFile.parse(public) as SettingsLoad.Broken).problem
        assertEquals(SettingsProblemKind.INVALID_VALUE, problem.kind)
        assertEquals("\$.providers[0].profile", problem.where)
    }
}
