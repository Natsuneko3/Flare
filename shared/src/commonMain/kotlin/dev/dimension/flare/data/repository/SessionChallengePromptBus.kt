package dev.dimension.flare.data.repository

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

public class SessionChallengePrompt internal constructor(
    public val exception: SessionChallengeException,
    private val onCompletedCallback: () -> Unit,
) {
    public fun complete() {
        onCompletedCallback()
    }
}

public object SessionChallengePromptBus {
    private val channel = Channel<SessionChallengePrompt>(Channel.BUFFERED)

    public val prompts: Flow<SessionChallengePrompt> = channel.receiveAsFlow()

    public fun emit(
        exception: SessionChallengeException,
        onCompleted: () -> Unit,
    ) {
        channel.trySend(
            SessionChallengePrompt(
                exception = exception,
                onCompletedCallback = onCompleted,
            ),
        )
    }

    @Throws(Exception::class)
    public suspend fun awaitPrompt(): SessionChallengePrompt = channel.receive()

    public fun tryReceive(): SessionChallengePrompt? = channel.tryReceive().getOrNull()

    internal fun clearForTesting() {
        while (channel.tryReceive().isSuccess) {
            // Drain buffered test prompts between test runs.
        }
    }
}
