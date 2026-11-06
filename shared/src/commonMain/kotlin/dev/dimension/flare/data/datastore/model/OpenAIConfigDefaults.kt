package dev.dimension.flare.data.datastore.model

import kotlinx.collections.immutable.persistentListOf

internal object OpenAIConfigDefaults {
    const val SERVER_URL = "https://api.openai.com/v1/"
    const val MODEL = "gpt-5.4-mini"

    // Offline examples, not a claim about the models available to a particular API key.
    // https://developers.openai.com/api/docs/models/gpt-5.4-mini
    val MODELS = persistentListOf(MODEL, "gpt-5.4", "gpt-5.4-nano")

    fun create(): AppSettings.AiConfig.Type.OpenAI = AppSettings.AiConfig.Type.OpenAI(serverUrl = SERVER_URL, apiKey = "", model = MODEL)

    fun applyToUnconfigured(config: AppSettings.AiConfig): AppSettings.AiConfig {
        val empty = AppSettings.AiConfig.Type.OpenAI("", "", "")
        return config.copy(
            type = if (config.type == empty) create() else config.type,
            agentType = if (config.agentType == empty) create() else config.agentType,
        )
    }
}
