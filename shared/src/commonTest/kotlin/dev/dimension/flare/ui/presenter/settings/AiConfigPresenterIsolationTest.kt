package dev.dimension.flare.ui.presenter.settings

import androidx.datastore.core.DataStoreFactory
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.RobolectricTest
import dev.dimension.flare.common.OnDeviceAI
import dev.dimension.flare.common.protobufSerializer
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.createDataStoreStorage
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.datastore.model.OpenAIConfigDefaults
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.network.ai.OpenAIService
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.ui.model.UiState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiConfigPresenterIsolationTest : RobolectricTest() {
    @Test
    fun twoPresentersInOneCompositionKeepIndependentState() = checkIsolation(sharedComposition = true)

    @Test
    fun twoPresenterCompositionsKeepIndependentState() = checkIsolation(sharedComposition = false)

    @Test
    fun defaultModelsCanBeSelectedWithoutApiKeyOrHttpRequests() = checkOfflineModels()

    @Test
    fun blankLegacySettingsMigrateBeforeOfflineModelSelection() =
        checkOfflineModels(
            AppSettings(
                version = "legacy",
                language = "zh-CN",
                aiConfig =
                    AppSettings.AiConfig(
                        type = AppSettings.AiConfig.Type.OpenAI("", "", ""),
                        agent = false,
                        tldr = true,
                        translatePrompt = "custom translate",
                        tldrPrompt = "custom summarize",
                    ),
            ),
        )

    private fun checkOfflineModels(legacySettings: AppSettings? = null) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val root = createTestRootPath()
            val fileStorage = OkioFileStorage(createTestFileSystem(), root)
            var scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val engine = MockEngine { respond("""{"data":[]}""") }
            val service = OpenAIService { HttpClient(engine) }
            val dataStore = AppDataStore(fileStorage)
            startKoin {
                modules(
                    module {
                        testSingle { dataStore }
                        testSingle { service }
                        testSingle<OnDeviceAI> {
                            object : OnDeviceAI {
                                override suspend fun isAvailable() = false

                                override suspend fun translate(
                                    source: String,
                                    targetLanguage: String,
                                    prompt: String,
                                ): String? = error("Unused")

                                override suspend fun tldr(
                                    source: String,
                                    targetLanguage: String,
                                    prompt: String,
                                ): String? = error("Unused")
                            }
                        }
                    },
                )
            }
            try {
                if (legacySettings != null) {
                    val seedJob = Job(backgroundScope.coroutineContext[Job])
                    try {
                        val seedStore =
                            DataStoreFactory.create(
                                storage =
                                    createDataStoreStorage(
                                        name = "app_settings.pb",
                                        serializer = protobufSerializer(AppSettings(version = "")),
                                        fileStorage = fileStorage,
                                    ),
                                scope = CoroutineScope(backgroundScope.coroutineContext + seedJob),
                            )
                        seedStore.updateData { legacySettings }
                    } finally {
                        seedJob.cancelAndJoin()
                    }
                }
                val original = legacySettings ?: AppSettings(version = "")
                val defaults = OpenAIConfigDefaults.create()
                val expected = original.copy(aiConfig = original.aiConfig.copy(type = defaults))
                assertEquals(expected, dataStore.appSettingsStore.data.first())

                val presenter = AiConfigPresenter()
                var states = moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.stateIn(scope)
                val initial = states.first { it.hasConfig(defaults) && it.hasOfflineModels() }
                assertEquals("", initial.openAIApiKey)
                assertEquals(0, engine.requestHistory.size)

                val selectedModel = OpenAIConfigDefaults.MODELS.last()
                assertTrue(selectedModel != defaults.model)
                initial.setOpenAIModel(selectedModel)
                val selectedConfig = defaults.copy(model = selectedModel)
                states.first { it.hasConfig(selectedConfig) && it.hasOfflineModels() }
                val stored = dataStore.appSettingsStore.data.first()
                assertEquals(expected.copy(aiConfig = expected.aiConfig.copy(type = selectedConfig)), stored)
                assertEquals("", (stored.aiConfig.type as AppSettings.AiConfig.Type.OpenAI).apiKey)
                assertNull(stored.aiConfig.agentType)
                assertFalse(stored.aiConfig.isAgentEnabled)

                scope.coroutineContext[Job]!!.cancelAndJoin()
                scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
                val reopenedPresenter = AiConfigPresenter()
                states = moleculeFlow(RecompositionMode.Immediate) { reopenedPresenter.body() }.stateIn(scope)
                val restored = states.first { it.hasConfig(selectedConfig) && it.hasOfflineModels() }
                assertEquals("", restored.openAIApiKey)
                assertEquals(0, engine.requestHistory.size)
            } finally {
                scope.coroutineContext[Job]!!.cancelAndJoin()
                engine.close()
                stopKoin()
                Dispatchers.resetMain()
                deleteTestRootPath(root)
            }
        }

    private fun checkIsolation(sharedComposition: Boolean) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val root = createTestRootPath()
            val dataStore = AppDataStore(OkioFileStorage(createTestFileSystem(), root))
            var scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val basic = AppSettings.AiConfig.Type.OpenAI("https://basic.example/", "basic-key", "light", "low", "{\"seed\":1}")
            val agent = AppSettings.AiConfig.Type.OpenAI("https://agent.example/", "agent-key", "strong", "high", "{\"seed\":2}")
            val service =
                OpenAIService {
                    HttpClient(
                        MockEngine { request ->
                            respond("""{"data":[{"id":"${request.url.host}:${request.headers[HttpHeaders.Authorization]}"}]}""")
                        },
                    )
                }
            startKoin {
                modules(
                    module {
                        testSingle { dataStore }
                        testSingle { service }
                        testSingle<OnDeviceAI> {
                            object : OnDeviceAI {
                                override suspend fun isAvailable() = true

                                override suspend fun translate(
                                    source: String,
                                    targetLanguage: String,
                                    prompt: String,
                                ): String? = error("Unused")

                                override suspend fun tldr(
                                    source: String,
                                    targetLanguage: String,
                                    prompt: String,
                                ): String? = error("Unused")
                            }
                        }
                    },
                )
            }
            try {
                dataStore.appSettingsStore.updateData { it.copy(aiConfig = AppSettings.AiConfig(type = basic, agentType = agent)) }
                var states = presenterStates(scope, sharedComposition)
                val initial = states.first { (base, override) -> base.hasConfig(basic) && override.hasConfig(agent) }
                assertFalse(initial.first.isAgentConfig)
                assertTrue(initial.second.isAgentConfig)
                assertFalse(initial.second.useSharedConfig)
                val loaded = states.first { (base, override) -> base.hasModels(basic) && override.hasModels(agent) }
                assertEquals(listOf(AiTypeOption.OnDevice, AiTypeOption.OpenAI), loaded.first.supportedTypes)
                assertEquals(listOf(AiTypeOption.OpenAI), loaded.second.supportedTypes)
                val reasoningEfforts = listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "max")
                assertEquals(reasoningEfforts, loaded.first.supportedOpenAIReasoningEfforts.map { it.value })
                assertEquals(reasoningEfforts, loaded.second.supportedOpenAIReasoningEfforts.map { it.value })

                // Dialog callbacks may outlive their snapshot; interleaved saves must use the latest stored config.
                val updatedBasic =
                    basic.copy(
                        serverUrl = "https://new-basic.example/",
                        apiKey = "new-basic-key",
                        model = "new-light",
                        reasoningEffort = "xhigh",
                        extraBody = "{\"seed\":3}",
                        contextWindow = 32768,
                    )
                val updatedAgent =
                    agent.copy(
                        serverUrl = "https://new-agent.example/",
                        apiKey = "new-agent-key",
                        model = "new-strong",
                        reasoningEffort = "max",
                        extraBody = "{\"seed\":4}",
                        contextWindow = 131072,
                    )
                initial.first.setOpenAIServerUrl(updatedBasic.serverUrl)
                initial.second.setOpenAIServerUrl(updatedAgent.serverUrl)
                initial.second.setOpenAIApiKey(updatedAgent.apiKey)
                initial.first.setOpenAIApiKey(updatedBasic.apiKey)
                initial.first.setOpenAIModel(updatedBasic.model)
                initial.second.setOpenAIModel(updatedAgent.model)
                initial.second.setOpenAIReasoningEffort(AiReasoningEffortOption.Max)
                initial.first.setOpenAIReasoningEffort(AiReasoningEffortOption.XHigh)
                assertTrue(initial.first.isValidOpenAIContextWindow(""))
                assertTrue(initial.first.isValidOpenAIContextWindow(" 32768 "))
                assertFalse(initial.first.isValidOpenAIContextWindow("512"))
                assertFalse(initial.first.isValidOpenAIContextWindow("invalid"))
                initial.first.setOpenAIContextWindow("32768")
                initial.second.setOpenAIContextWindow("131072")
                initial.first.setOpenAIExtraBody(updatedBasic.extraBody)
                initial.second.setOpenAIExtraBody(updatedAgent.extraBody)
                states.first { (base, override) -> base.hasConfig(updatedBasic) && override.hasConfig(updatedAgent) }
                states.first { (base, override) -> base.hasModels(updatedBasic) && override.hasModels(updatedAgent) }
                assertEquals(
                    AppSettings.AiConfig(type = updatedBasic, agentType = updatedAgent),
                    dataStore.appSettingsStore.data
                        .first()
                        .aiConfig,
                )

                scope.cancel()
                scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
                states = presenterStates(scope, sharedComposition)
                val restored = states.first { (base, override) -> base.hasConfig(updatedBasic) && override.hasConfig(updatedAgent) }
                restored.second.setUseSharedConfig(true)
                val inherited = states.first { (_, override) -> override.useSharedConfig && override.hasConfig(updatedBasic) }
                assertNull(
                    dataStore.appSettingsStore.data
                        .first()
                        .aiConfig.agentType,
                )
                inherited.first.setOpenAIModel("inherited-model")
                val changedBasic = updatedBasic.copy(model = "inherited-model")
                val following = states.first { (base, override) -> base.hasConfig(changedBasic) && override.hasConfig(changedBasic) }
                following.second.setUseSharedConfig(false)
                val independent = states.first { (_, override) -> !override.useSharedConfig && override.hasConfig(changedBasic) }
                independent.second.setOpenAIModel("independent-again")
                states.first { (base, override) ->
                    base.hasConfig(changedBasic) &&
                        override.hasConfig(changedBasic.copy(model = "independent-again"))
                }
                assertEquals(
                    changedBasic,
                    dataStore.appSettingsStore.data
                        .first()
                        .aiConfig.type,
                )
                for ((basicEffort, agentEffort) in loaded.first.supportedOpenAIReasoningEfforts.zip(
                    loaded.second.supportedOpenAIReasoningEfforts.reversed(),
                )) {
                    independent.first.setOpenAIReasoningEffort(basicEffort)
                    independent.second.setOpenAIReasoningEffort(agentEffort)
                    val expectedBasic = changedBasic.copy(reasoningEffort = basicEffort.value)
                    val expectedAgent = changedBasic.copy(model = "independent-again", reasoningEffort = agentEffort.value)
                    states.first { (base, override) -> base.hasConfig(expectedBasic) && override.hasConfig(expectedAgent) }
                    assertEquals(
                        AppSettings.AiConfig(type = expectedBasic, agentType = expectedAgent),
                        dataStore.appSettingsStore.data
                            .first()
                            .aiConfig,
                    )
                }
            } finally {
                scope.cancel()
                stopKoin()
                Dispatchers.resetMain()
                deleteTestRootPath(root)
            }
        }

    private suspend fun presenterStates(
        scope: CoroutineScope,
        sharedComposition: Boolean,
    ) = if (sharedComposition) {
        val basic = AiConfigPresenter()
        val agent = AiConfigPresenter(agentConfig = true)
        moleculeFlow(RecompositionMode.Immediate) { basic.body() to agent.body() }
    } else {
        val basic = AiConfigPresenter()
        val agent = AiConfigPresenter(agentConfig = true)
        combine(
            moleculeFlow(RecompositionMode.Immediate) { basic.body() },
            moleculeFlow(RecompositionMode.Immediate) { agent.body() },
        ) { base, override -> base to override }
    }.stateIn(scope)

    private fun AiConfigPresenter.State.hasConfig(config: AppSettings.AiConfig.Type.OpenAI) =
        openAIServerUrl == config.serverUrl && openAIApiKey == config.apiKey && openAIModel == config.model &&
            openAIReasoningEffort.value == config.reasoningEffort && openAIExtraBody == config.extraBody &&
            openAIContextWindow == config.contextWindow?.toString().orEmpty()

    private fun AiConfigPresenter.State.hasModels(config: AppSettings.AiConfig.Type.OpenAI) =
        (openAIModels as? UiState.Success)?.data ==
            listOf("${config.serverUrl.removePrefix("https://").removeSuffix("/")}:Bearer ${config.apiKey}")

    private fun AiConfigPresenter.State.hasOfflineModels() = (openAIModels as? UiState.Success)?.data == OpenAIConfigDefaults.MODELS
}
