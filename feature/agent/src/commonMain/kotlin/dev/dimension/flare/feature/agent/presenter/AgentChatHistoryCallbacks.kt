package dev.dimension.flare.feature.agent.presenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.dimension.flare.feature.agent.common.AgentChatHistoryProvider
import dev.dimension.flare.feature.agent.common.AgentInputRequest

@Composable
internal fun rememberAgentChatHistoryCallbacks(
    historyProvider: AgentChatHistoryProvider,
    conversationId: String,
): AgentChatHistoryCallbacks =
    remember(historyProvider, conversationId) {
        AgentChatHistoryCallbacks(historyProvider, conversationId)
    }

internal class AgentChatHistoryCallbacks(
    private val historyProvider: AgentChatHistoryProvider,
    private val conversationId: String,
) {
    suspend fun userMessageSubmitted(text: String) = historyProvider.storeUserUiMessage(conversationId, text)

    suspend fun inputRequestOptionSubmitted(option: AgentInputRequest.Option) =
        historyProvider.storeUserUiInputRequestOption(conversationId, option)

    suspend fun inputRequestSelected(
        requestId: String,
        optionId: String,
    ) = historyProvider.markInputRequestSelected(conversationId, requestId, optionId)

    suspend fun agentRunCompleted() = historyProvider.generateTitleIfNeeded(conversationId)

    suspend fun roomRuntimeStateChanged(isRunning: Boolean) =
        historyProvider.updateRoomState(
            conversationId = conversationId,
            isRunning = isRunning,
            updateErrorMessage = false,
        )

    suspend fun roomStateChanged(errorMessage: String?) =
        historyProvider.updateRoomState(
            conversationId = conversationId,
            errorMessage = errorMessage,
        )
}
