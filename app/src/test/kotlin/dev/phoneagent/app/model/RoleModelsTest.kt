package dev.phoneagent.app.model

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.settings.RoleBinding
import dev.phoneagent.app.model.settings.SettingsFixtures
import dev.phoneagent.app.model.settings.SettingsFixtures.DEEPSEEK
import dev.phoneagent.app.model.settings.SettingsFixtures.MAIN
import dev.phoneagent.app.model.settings.SettingsFixtures.SEEING
import dev.phoneagent.app.model.settings.SettingsFixtures.SMALL
import dev.phoneagent.app.model.settings.StoredModel
import dev.phoneagent.core.model.BlobSource
import dev.phoneagent.core.model.ModelRole
import dev.phoneagent.core.model.openai.ChatQuirks
import dev.phoneagent.core.model.openai.OpenAiAdapters
import dev.phoneagent.core.model.openai.WireOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class RoleModelsTest {
    private val adapters = OpenAiAdapters({ _, _ -> null })
    private val blobs = BlobSource { throw IOException("no images here") }

    @Test
    fun lightAndVisionFollowTheMainModelUnlessNamed() {
        val follows = RoleModels(SettingsFixtures.settings(), adapters)
        for (role in ModelRole.entries) assertEquals(MAIN, follows.target(role).model.id)
        val own =
            RoleModels(
                SettingsFixtures.settings(
                    roles =
                        mapOf(
                            ModelRole.MAIN to RoleBinding(DEEPSEEK, MAIN),
                            ModelRole.LIGHT to RoleBinding(DEEPSEEK, SMALL),
                            ModelRole.VISION to RoleBinding(DEEPSEEK, SEEING),
                        ),
                ),
                adapters,
            )
        assertEquals(
            listOf(MAIN, SMALL, SEEING),
            listOf(ModelRole.MAIN, ModelRole.LIGHT, ModelRole.VISION).map {
                own.target(it).model.id
            },
        )
    }

    @Test
    fun modelsUseTheSavedCapabilitiesAndOptions() {
        val wire = WireOptions(chat = ChatQuirks(parallelToolCalls = false, streamUsage = false))
        val settings = SettingsFixtures.settings(providers = listOf(SettingsFixtures.provider(wire = wire)))
        val model = RoleModels(settings, adapters).model(ModelRole.MAIN, blobs)
        assertEquals(MAIN, model.info.model)
        assertEquals(DEEPSEEK, model.info.provider)
        assertEquals(SettingsFixtures.verified, model.info.capabilities)
        assertEquals(false, model.prepare(null).wire.streamOptions)
        val defaults = RoleModels(SettingsFixtures.settings(), adapters).model(ModelRole.MAIN, blobs).prepare(null).wire
        assertEquals(true, defaults.streamOptions)
    }

    @Test
    fun aSnapshotIsKeptWhenTheSettingsChangeLater() {
        val models = mutableListOf(StoredModel(MAIN, SettingsFixtures.verified))
        val provider = SettingsFixtures.provider(models = models)
        val roles = RoleModels(SettingsFixtures.settings(providers = listOf(provider)), adapters)
        models[0] = StoredModel(MAIN, SettingsFixtures.verified.copy(contextTokens = 1))
        assertEquals(SettingsFixtures.verified, roles.model(ModelRole.MAIN, blobs).info.capabilities)
    }

    @Test
    fun settingsThatWouldNotLoadAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            RoleModels(SettingsFixtures.settings(roles = emptyMap()), adapters)
        }
    }
}
