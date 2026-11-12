package dev.dimension.flare.feature.agent.runtime

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import dev.dimension.flare.common.JSON
import dev.dimension.flare.data.datastore.model.AppSettings
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class AiConfigKoogBridge {
    fun availability(aiConfig: AppSettings.AiConfig): AgentAvailability =
        when (val type = aiConfig.effectiveAgentType) {
            AppSettings.AiConfig.Type.OnDevice -> {
                AgentAvailability.Unavailable(AgentAvailability.Reason.OnDeviceAiUnsupported)
            }

            is AppSettings.AiConfig.Type.OpenAI -> {
                when {
                    type.serverUrl.isBlank() -> {
                        AgentAvailability.Unavailable(AgentAvailability.Reason.MissingOpenAIEndpoint)
                    }

                    type.apiKey.isBlank() -> {
                        AgentAvailability.Unavailable(AgentAvailability.Reason.MissingOpenAIApiKey)
                    }

                    type.model.isBlank() -> {
                        AgentAvailability.Unavailable(AgentAvailability.Reason.MissingOpenAIModel)
                    }

                    else -> {
                        AgentAvailability.Available
                    }
                }
            }
        }

    fun createRuntime(
        aiConfig: AppSettings.AiConfig,
        httpClientFactory: KoogHttpClient.Factory,
    ): FlareAgentRuntime? {
        if (availability(aiConfig) != AgentAvailability.Available) {
            return null
        }

        val openAIConfig = aiConfig.effectiveAgentType as? AppSettings.AiConfig.Type.OpenAI ?: return null
        val contextWindow =
            openAIConfig.contextWindow?.also { require(it >= 1_024) { "Context window must be at least 1024 tokens" } }?.toLong()
                ?: OpenAIModels.models.firstOrNull { it.id == openAIConfig.model }?.contextLength
        val extraBody = openAIConfig.extraBody.takeIf { it.isNotBlank() }?.let { JSON.parseToJsonElement(it).jsonObject }
        // GPT-6 tool calls require Responses; other compatible providers retain Chat Completions.
        val modelId = openAIConfig.model.substringAfterLast('/')
        val useResponses = modelId.startsWith("gpt-6-") || modelId.startsWith("gpt-6.")
        // Omit optional sampling settings for reasoning families, including provider-prefixed model IDs.
        val supportsTemperature =
            !useResponses &&
                modelId != "gpt-5" &&
                !modelId.startsWith("gpt-5-") &&
                !modelId.startsWith("gpt-5.") &&
                !Regex("o\\d+(-.*)?").matches(modelId)
        val params =
            LLMParams(
                additionalProperties =
                    buildJsonObject {
                        extraBody
                            ?.filterKeys {
                                it !in AGENT_REQUEST_FIELDS &&
                                    (!useResponses || it !in RESPONSES_REQUEST_FIELDS) &&
                                    (supportsTemperature || it != "temperature")
                            }
                            ?.forEach { (key, value) -> put(key, value) }
                        if (useResponses) {
                            val reasoning =
                                buildJsonObject {
                                    extraBody?.get("reasoning_effort")?.let { put("effort", it) }
                                    extraBody?.get("reasoning")?.jsonObject?.forEach { (key, value) -> put(key, value) }
                                    openAIConfig.reasoningEffort.takeIf { it.isNotBlank() }?.let { put("effort", it) }
                                }
                            if (reasoning.isNotEmpty()) put("reasoning", reasoning)
                            // The agent replays its own history, including opaque reasoning from tool calls.
                            put("store", false)
                            put(
                                "include",
                                buildJsonArray {
                                    extraBody?.get("include")?.jsonArray?.forEach { add(it) }
                                    add("reasoning.encrypted_content")
                                },
                            )
                        } else {
                            openAIConfig.reasoningEffort.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
                        }
                    },
            )
        val client =
            OpenAILLMClient(
                apiKey = openAIConfig.apiKey,
                settings =
                    OpenAIClientSettings(
                        baseUrl = openAIConfig.serverUrl.trimEnd('/'),
                        chatCompletionsPath = "chat/completions",
                        responsesAPIPath = "responses",
                        embeddingsPath = "embeddings",
                        moderationsPath = "moderations",
                        modelsPath = "models",
                    ),
                httpClientFactory = if (useResponses) ResponsesHttpClientFactory(httpClientFactory) else httpClientFactory,
            )
        val model =
            LLModel(
                provider = LLMProvider.OpenAI,
                id = openAIConfig.model,
                capabilities =
                    buildList {
                        add(LLMCapability.Completion)
                        if (useResponses) {
                            add(LLMCapability.OpenAIEndpoint.Responses)
                            add(LLMCapability.Thinking)
                        } else {
                            add(LLMCapability.OpenAIEndpoint.Completions)
                        }
                        if (supportsTemperature) add(LLMCapability.Temperature)
                        add(LLMCapability.Tools)
                        add(LLMCapability.ToolChoice)
                        add(LLMCapability.Vision.Image)
                    },
                contextLength = contextWindow,
            )

        return FlareAgentRuntime(
            promptExecutor = MultiLLMPromptExecutor(client),
            model = model,
            params = params,
        )
    }

    private companion object {
        // The agent owns conversation history, tool selection and response transport.
        val AGENT_REQUEST_FIELDS =
            setOf(
                "model",
                "messages",
                "tools",
                "tool_choice",
                "stream",
                "stream_options",
                "n",
            )
        val RESPONSES_REQUEST_FIELDS = setOf("input", "instructions", "previous_response_id", "conversation", "reasoning_effort")
    }
}
