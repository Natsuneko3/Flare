package dev.dimension.flare.data.datastore.model

import dev.dimension.flare.data.translation.preTranslationExecutionKey
import dev.dimension.flare.data.translation.translationProviderCacheKey
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class AgentAiConfigTest {
    private val basic = AppSettings.AiConfig.Type.OpenAI("https://basic.example/v1/", "basic-key", "light", "low", "{\"seed\":1}")
    private val agent = AppSettings.AiConfig.Type.OpenAI("https://agent.example/v1/", "agent-key", "strong", "high", "{\"seed\":2}")

    @Test
    fun defaultOpenAIConfigHasEndpointAndModelWithoutCredentials() {
        val config = AppSettings.AiConfig()
        val openAI = config.type as AppSettings.AiConfig.Type.OpenAI
        assertEquals("https://api.openai.com/v1/", openAI.serverUrl)
        assertEquals("gpt-5.4-mini", openAI.model)
        assertEquals("", openAI.apiKey)
        assertNull(config.agentType)
        assertEquals(openAI, config.effectiveAgentType)
        assertFalse(config.isAgentEnabled)
    }

    @Test
    fun emptyLegacyConfigReceivesDefaultsAndPreservesInheritanceAndOptions() {
        val empty = AppSettings.AiConfig.Type.OpenAI("", "", "")
        val legacy =
            AppSettings.AiConfig(
                type = empty,
                agent = false,
                tldr = true,
                translatePrompt = "custom translate",
                tldrPrompt = "custom summarize",
            )
        val migrated = OpenAIConfigDefaults.applyToUnconfigured(legacy)
        assertEquals(legacy.copy(type = OpenAIConfigDefaults.create()), migrated)
        assertNull(migrated.agentType)
        assertEquals(migrated.type, migrated.effectiveAgentType)
        assertEquals(migrated, OpenAIConfigDefaults.applyToUnconfigured(migrated))

        val independent = AppSettings.AiConfig(type = basic, agentType = empty)
        assertEquals(
            independent.copy(agentType = OpenAIConfigDefaults.create()),
            OpenAIConfigDefaults.applyToUnconfigured(independent),
        )
        val onDevice = AppSettings.AiConfig(type = AppSettings.AiConfig.Type.OnDevice)
        assertEquals(onDevice, OpenAIConfigDefaults.applyToUnconfigured(onDevice))
    }

    @Test
    fun defaultMigrationPreservesEveryPartiallyConfiguredOpenAIField() {
        val empty = AppSettings.AiConfig.Type.OpenAI("", "", "")
        val configured =
            listOf(
                empty.copy(serverUrl = "https://custom.example/v1/"),
                empty.copy(apiKey = "custom-key"),
                empty.copy(model = "custom-model"),
                empty.copy(reasoningEffort = "high"),
                empty.copy(extraBody = "{\"seed\":1}"),
                empty.copy(contextWindow = 32768),
            )
        configured.forEach { type ->
            val inherited = AppSettings.AiConfig(type = type)
            assertEquals(inherited, OpenAIConfigDefaults.applyToUnconfigured(inherited))
            val independent = inherited.copy(agentType = type)
            assertEquals(independent, OpenAIConfigDefaults.applyToUnconfigured(independent))
        }
    }

    @Test
    fun legacyProtobufAndJsonInheritAllBasicSettings() {
        val legacy =
            LegacyAgentInheritanceConfig(type = basic, tldr = true, agent = false, translatePrompt = "translate", tldrPrompt = "summarize")
        val decoded =
            listOf(
                ProtoBuf.decodeFromByteArray(
                    AppSettings.AiConfig.serializer(),
                    ProtoBuf.encodeToByteArray(LegacyAgentInheritanceConfig.serializer(), legacy),
                ),
                Json.decodeFromString(
                    AppSettings.AiConfig.serializer(),
                    Json.encodeToString(LegacyAgentInheritanceConfig.serializer(), legacy),
                ),
            )
        decoded.forEach { config ->
            assertNull(config.agentType)
            assertEquals(basic, config.effectiveAgentType)
            assertFalse(config.agent)
            assertTrue(config.tldr)
            assertEquals("translate", config.translatePrompt)
            assertEquals("summarize", config.tldrPrompt)
        }
    }

    @Test
    fun independentSettingsRoundTripWithoutChangingBasicSettings() {
        for (basicType in listOf(basic, AppSettings.AiConfig.Type.OnDevice)) {
            val settings = AppSettings(version = "test", aiConfig = AppSettings.AiConfig(type = basicType, agentType = agent))
            val decoded =
                listOf(
                    ProtoBuf.decodeFromByteArray(AppSettings.serializer(), ProtoBuf.encodeToByteArray(AppSettings.serializer(), settings)),
                    Json.decodeFromString(AppSettings.serializer(), Json.encodeToString(AppSettings.serializer(), settings)),
                )
            decoded.forEach {
                assertEquals(settings, it)
                assertEquals(agent, it.aiConfig.effectiveAgentType)
                assertTrue(it.aiConfig.isAgentEnabled)
            }
        }
    }

    @Test
    fun independentConfigDoesNotFallBackWhenIncomplete() {
        val settings = AppSettings.AiConfig(type = basic, agentType = agent.copy(model = ""))
        assertEquals("", (settings.effectiveAgentType as AppSettings.AiConfig.Type.OpenAI).model)
        assertFalse(settings.isAgentEnabled)
        assertFalse(settings.copy(agentType = agent.copy(serverUrl = " ")).isAgentEnabled)
        assertFalse(settings.copy(agentType = agent.copy(apiKey = " ")).isAgentEnabled)
        assertFalse(settings.copy(agentType = agent, agent = false).isAgentEnabled)
        assertFalse(AppSettings.AiConfig().isAgentEnabled)
    }

    @Test
    fun changingAgentDoesNotInvalidateTranslationCacheOrExecution() {
        val settings =
            AppSettings(
                version = "test",
                aiConfig = AppSettings.AiConfig(type = basic),
                translateConfig = AppSettings.TranslateConfig(preTranslate = true, provider = AppSettings.TranslateConfig.Provider.AI),
            )
        val changed = settings.copy(aiConfig = settings.aiConfig.copy(agentType = agent))
        assertEquals(settings.translationProviderCacheKey(), changed.translationProviderCacheKey())
        assertEquals(settings.preTranslationExecutionKey(), changed.preTranslationExecutionKey())
    }
}

@Serializable
private data class LegacyAgentInheritanceConfig(
    val translation: Boolean = false,
    val tldr: Boolean = false,
    val type: AppSettings.AiConfig.Type,
    val translatePrompt: String,
    val tldrPrompt: String,
    val preTranslation: Boolean = false,
    val agent: Boolean = true,
)
