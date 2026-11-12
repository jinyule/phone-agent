package dev.phoneagent.app.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.app.model.setup.FormProblem
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
import dev.phoneagent.core.model.openai.ProviderPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The screen's "test and save" always ends in a state the screen shows, and keeps what it saved. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ModelSettingsViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val provider = FakeProvider()
    private val cipher = SoftwareCipher()
    private val services by lazy {
        ModelServices(temp.newFolder("files"), temp.newFolder("no_backup"), cipher, io = UnconfinedTestDispatcher())
    }
    private val form get() =
        ProviderForm.start(null).copy(baseUrl = provider.baseUrl, apiKey = KEY, mainModel = "main-model")

    @Before
    fun main() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun stop() {
        Dispatchers.resetMain()
        provider.close()
    }

    /** Runs "test and save" of [form] on a new view model; the state it ends in, and the model. */
    private fun run(form: ProviderForm): Pair<RunState, ModelSettingsViewModel> {
        val model = ModelSettingsViewModel(services)
        model.edit(form)
        model.testAndSave()
        val end = runBlocking { withTimeout(WAIT_MILLIS) { model.run.first { it !is RunState.Testing } } }
        return end to model
    }

    /** The end state of [form]'s run, or the class of what kept it from ending. */
    private fun endOf(form: ProviderForm): Any = runCatching { run(form).first }.getOrElse { it.javaClass.simpleName }

    @Test
    fun addressesAndHeaderValuesTheHttpClientRefusesEndAsFormProblems() {
        val addresses =
            listOf("http://127.0.0.1:0/v1", "http://127.0.0.1:65536/v1", "http://192.168.1.5:114341/v1")
                .associateWith { endOf(form.copy(baseUrl = it)) }
        val header = mapOf("header café" to endOf(form.copy(headers = mapOf("X-Note" to "café"))))
        assertEquals(
            addresses.mapValues { RunState.Done(SetupOutcome.Invalid(listOf(FormProblem.BASE_URL_INVALID))) } +
                header.mapValues { RunState.Done(SetupOutcome.Invalid(listOf(FormProblem.HEADER_INVALID))) },
            addresses + header,
        )
        assertEquals(emptyList<FakeProvider.Seen>(), provider.requests)
    }

    @Test
    fun aSavedFormShowsTheContextWindowItSaved() {
        val (end, model) = run(form.copy(contextTokens = 16_000))
        assertTrue(end.toString(), (end as RunState.Done).outcome is SetupOutcome.Saved)
        assertEquals(16_000, model.form.value.contextTokens)
        assertEquals(16_000, ModelSettingsViewModel(services).form.value.contextTokens)
    }

    @Test
    fun anUnexpectedFailureEndsTheRunAsAnErrorAndTheNextRunWorks() {
        cipher.sealFailure = IllegalStateException("not a real failure")
        val (end, model) = run(form)
        assertEquals(RunState.Error, end)
        cipher.sealFailure = null
        model.testAndSave()
        val again = runBlocking { withTimeout(WAIT_MILLIS) { model.run.first { it is RunState.Done } } }
        assertTrue(again.toString(), (again as RunState.Done).outcome is SetupOutcome.Saved)
    }

    @Test
    fun theFormCannotChangeWhileItIsBeingTested() {
        val hold = provider.holdTrials()
        val model = ModelSettingsViewModel(services)
        model.edit(form)
        model.testAndSave()
        check(hold.arrived()) { "the trial never came" }
        model.edit(model.form.value.copy(mainModel = "newer-model"))
        val afterEdit = model.form.value.mainModel
        model.choosePreset(ProviderPresets.DEEPSEEK)
        val afterPreset = model.form.value.presetId
        hold.release()
        val end = runBlocking { withTimeout(WAIT_MILLIS) { model.run.first { it !is RunState.Testing } } }
        val shownAfterSave = model.form.value.mainModel
        model.edit(model.form.value.copy(mainModel = "newer-model"))
        // Collected first, so a run that takes edits meanwhile shows where they went.
        assertEquals(
            listOf("main-model", null, "Saved", "main-model", "newer-model"),
            listOf(
                afterEdit,
                afterPreset,
                (end as? RunState.Done)?.outcome?.javaClass?.simpleName,
                shownAfterSave,
                model.form.value.mainModel,
            ),
        )
    }

    @Test
    fun aNewMainModelTakesTheContextWindowStoredForIt() {
        provider.listing =
            """{"object":"list","data":[{"id":"main-model","context_length":32768},""" +
            """{"id":"small","context_length":8192}]}"""
        val (end, _) = run(form.copy(lightModel = "small", contextTokens = 16_000))
        check((end as RunState.Done).outcome is SetupOutcome.Saved) { end }

        /** The context window a view model on the saved settings shows after [edits], one by one. */
        fun after(vararg edits: ProviderForm.() -> ProviderForm): Int? {
            val model = ModelSettingsViewModel(services)
            for (edit in edits) model.edit(model.form.value.edit())
            return model.form.value.contextTokens
        }
        val typed: ProviderForm.() -> ProviderForm = { copy(contextTokens = 20_000) }
        // Collected first, so each edit shows the window it leaves.
        val seen =
            mapOf(
                "main model changed to one the settings store" to after({ copy(mainModel = "small") }),
                "main model changed to one they do not store" to after({ copy(mainModel = "fresh-model") }),
                "main model changed only by spaces" to after({ copy(mainModel = "main-model ") }),
                "main model and window changed at once" to after({ copy(mainModel = "small", contextTokens = 20_000) }),
                "window typed, main model kept" to after(typed),
                "window typed, then the light model changed" to after(typed, { copy(lightModel = "") }),
                "window typed, then the main model changed" to after(typed, { copy(mainModel = "small") }),
            )
        assertEquals(
            mapOf(
                "main model changed to one the settings store" to 8_192,
                "main model changed to one they do not store" to null,
                "main model changed only by spaces" to 16_000,
                "main model and window changed at once" to 20_000,
                "window typed, main model kept" to 20_000,
                "window typed, then the light model changed" to 20_000,
                "window typed, then the main model changed" to 8_192,
            ),
            seen,
        )
    }

    @Test
    fun aNewMainModelKeepsItsContextWindowThroughSaveAndReopen() {
        provider.listing =
            """{"object":"list","data":[{"id":"main-model","context_length":32768},""" +
            """{"id":"small","context_length":8192}]}"""
        val (end, _) = run(form.copy(lightModel = "small", contextTokens = 16_000))
        check((end as RunState.Done).outcome is SetupOutcome.Saved) { end }

        /** After the main model became [main] and the form was saved: stored, shown, and shown on reopening. */
        fun saved(main: String): List<Int?> {
            val model = ModelSettingsViewModel(services)
            model.edit(model.form.value.copy(mainModel = main))
            model.testAndSave()
            val done = runBlocking { withTimeout(WAIT_MILLIS) { model.run.first { it is RunState.Done } } }
            val settings = ((done as RunState.Done).outcome as? SetupOutcome.Saved)?.settings
            val stored =
                settings
                    ?.providers
                    ?.single()
                    ?.models
                    ?.firstOrNull { it.id.value == main }
                    ?.capabilities
                    ?.contextTokens
            return listOf(
                stored,
                model.form.value.contextTokens,
                ModelSettingsViewModel(services).form.value.contextTokens,
            )
        }
        // Collected first; the second save starts from what the first one saved.
        val seen = mapOf("small" to saved("small"), "fresh-model" to saved("fresh-model"))
        assertEquals(
            mapOf(
                "small" to listOf(8_192, 8_192, 8_192),
                "fresh-model" to List(3) { ProviderForm.DEFAULT_CONTEXT_TOKENS },
            ),
            seen,
        )
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
        const val WAIT_MILLIS = 10_000L
    }
}
