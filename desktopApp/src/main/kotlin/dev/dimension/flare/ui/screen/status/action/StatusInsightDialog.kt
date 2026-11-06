package dev.dimension.flare.ui.screen.status.action

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.Robot
import dev.dimension.flare.Res
import dev.dimension.flare.agent_chat_input_placeholder
import dev.dimension.flare.agent_chat_send
import dev.dimension.flare.feature.agent.presenter.status.StatusInsightPresenter
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ok
import dev.dimension.flare.status_insight_analyzing
import dev.dimension.flare.status_insight_error
import dev.dimension.flare.status_insight_title
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.agent.AgentChatScaffold
import dev.dimension.flare.ui.component.agent.label
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.presenter.invoke
import dev.dimension.flare.ui.route.Route
import io.github.composefluent.FluentTheme
import io.github.composefluent.LocalContentColor
import io.github.composefluent.LocalTextStyle
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.FluentDialog
import io.github.composefluent.component.Text
import moe.tlaster.precompose.molecule.producePresenter
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StatusInsightDialog(
    accountType: AccountType,
    statusKey: MicroBlogKey,
    onBack: () -> Unit,
    navigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by producePresenter("status_insight_${accountType}_$statusKey") {
        remember(accountType, statusKey) {
            StatusInsightPresenter(
                accountType = accountType,
                statusKey = statusKey,
            )
        }.invoke()
    }

    FluentDialog(
        visible = true,
    ) {
        Column(
            modifier =
                modifier
                    .onKeyEvent {
                        if (it.key == Key.Escape) {
                            onBack()
                            true
                        } else {
                            false
                        }
                    }.width(560.dp)
                    .heightIn(max = 720.dp)
                    .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FAIcon(
                    imageVector = FontAwesomeIcons.Solid.Robot,
                    contentDescription = null,
                )
                Text(
                    text = stringResource(Res.string.status_insight_title),
                    style = FluentTheme.typography.title,
                )
            }

            AgentChatScaffold(
                messages = state.messages,
                input = state.input,
                isRunning = state.room.isRunning,
                canSend = state.canSend,
                error = state.room.error,
                onRetry = state::retry,
                runningTrace = state.room.currentTrace?.label() ?: stringResource(Res.string.status_insight_analyzing),
                inputPlaceholder = stringResource(Res.string.agent_chat_input_placeholder),
                sendContentDescription = stringResource(Res.string.agent_chat_send),
                onInputChange = state::setInput,
                onSend = state::sendMessage,
                onInputRequestOptionSelected = state::selectInputRequestOption,
                onPostClick = { post ->
                    navigate(Route.StatusDetail(accountType = post.accountType, statusKey = post.statusKey))
                },
                onUserClick = { user ->
                    user.toRoute()?.let(navigate)
                },
                modifier =
                    Modifier
                        .weight(1f, fill = true),
            )

            AccentButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(Res.string.ok))
            }
        }
    }
}

private fun UiProfile.toRoute(): Route? =
    when (val event = clickEvent) {
        is ClickEvent.Deeplink -> Route.parse(event.url)
        ClickEvent.Noop -> null
    }

@Composable
private fun StatusInsightCurrentTrace(trace: String) {
    val transition = rememberInfiniteTransition()
    val shimmerOffset by transition.animateFloat(
        initialValue = -240f,
        targetValue = 480f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
    )
    val color = LocalContentColor.current
    val shimmerBrush =
        Brush.linearGradient(
            colors =
                listOf(
                    color.copy(alpha = 0.35f),
                    color,
                    color.copy(alpha = 0.35f),
                ),
            start = Offset(shimmerOffset, 0f),
            end = Offset(shimmerOffset + 180f, 0f),
        )
    BasicText(
        text = trace,
        style =
            LocalTextStyle.current.merge(
                TextStyle(brush = shimmerBrush),
            ),
    )
}
