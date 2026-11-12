package dev.phoneagent.app.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.phoneagent.app.model.ModelServices
import dev.phoneagent.app.model.credential.SoftwareCipher
import dev.phoneagent.app.model.setup.FakeProvider
import dev.phoneagent.app.model.setup.FormProblem
import dev.phoneagent.app.model.setup.ProviderForm
import dev.phoneagent.app.model.setup.SetupOutcome
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
    fun anUnexpectedFailureEndsTheRunAsAnErrorAndTheNextRunWorks() {
        cipher.sealFailure = IllegalStateException("not a real failure")
        val (end, model) = run(form)
        assertEquals(RunState.Error, end)
        cipher.sealFailure = null
        model.testAndSave()
        val again = runBlocking { withTimeout(WAIT_MILLIS) { model.run.first { it is RunState.Done } } }
        assertTrue(again.toString(), (again as RunState.Done).outcome is SetupOutcome.Saved)
    }

    private companion object {
        const val KEY = "sk-test-not-a-real-key"
        const val WAIT_MILLIS = 10_000L
    }
}
