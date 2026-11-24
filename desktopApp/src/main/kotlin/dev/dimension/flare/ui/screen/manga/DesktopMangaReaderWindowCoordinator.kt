package dev.dimension.flare.ui.screen.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import dev.dimension.flare.Res
import dev.dimension.flare.app_name
import dev.dimension.flare.feature.plugin.management.ProvidePluginTextResolverV1
import dev.dimension.flare.flare_logo
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.component.PlatformTitleBar
import dev.dimension.flare.ui.component.PlatformWindow
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.Route
import dev.dimension.flare.ui.screen.serviceselect.ProvideDesktopSessionChallengeHost
import dev.dimension.flare.ui.theme.FlareTheme
import dev.dimension.flare.ui.theme.ProvideComposeWindow
import dev.dimension.flare.ui.theme.ProvideNucleusDecoratedWindowTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Immutable
internal data class SessionKey(
    val accountType: AccountType,
    val mangaKey: MicroBlogKey,
)

@Immutable
internal data class DesktopMangaReaderWindowSession(
    val key: SessionKey,
    val chapterId: String,
    val initialPage: Int?,
    val requestNonce: Int = 0,
    val focusRequestToken: Int = 0,
) {
    val accountType: AccountType
        get() = key.accountType

    val mangaKey: MicroBlogKey
        get() = key.mangaKey
}

@Stable
internal class DesktopMangaReaderWindowCoordinator {
    private val _sessions = mutableStateListOf<DesktopMangaReaderWindowSession>()

    val sessions: List<DesktopMangaReaderWindowSession>
        get() = _sessions

    fun open(route: Route.Manga.Reader) {
        val key = SessionKey(accountType = route.accountType, mangaKey = route.mangaKey)
        val index = _sessions.indexOfFirst { it.key == key }
        if (index >= 0) {
            val current = _sessions[index]
            _sessions[index] =
                current.copy(
                    chapterId = route.chapterId,
                    initialPage = route.initialPage,
                    requestNonce = current.requestNonce + 1,
                    focusRequestToken = current.focusRequestToken + 1,
                )
        } else {
            _sessions.add(
                DesktopMangaReaderWindowSession(
                    key = key,
                    chapterId = route.chapterId,
                    initialPage = route.initialPage,
                    requestNonce = 0,
                    focusRequestToken = 1,
                ),
            )
        }
    }

    fun close(key: SessionKey) {
        _sessions.removeAll { it.key == key }
    }
}

internal val LocalDesktopMangaReaderWindowCoordinator =
    staticCompositionLocalOf<DesktopMangaReaderWindowCoordinator?> { null }

internal fun shouldCloseDesktopWindowOnEscape(event: KeyEvent): Boolean = event.type == KeyEventType.KeyDown && event.key == Key.Escape

@Composable
internal fun DesktopMangaReaderWindowsHost(coordinator: DesktopMangaReaderWindowCoordinator) {
    coordinator.sessions.forEach { session ->
        key(session.key) {
            ProvideNucleusDecoratedWindowTheme(isDark = true) {
                PlatformWindow(
                    state =
                        rememberWindowState(
                            size =
                                DpSize(
                                    width = 1080.dp,
                                    height = 840.dp,
                                ),
                        ),
                    onCloseRequest = { coordinator.close(session.key) },
                    title = stringResource(Res.string.app_name),
                    icon = painterResource(Res.drawable.flare_logo),
                    onKeyEvent = { event ->
                        if (shouldCloseDesktopWindowOnEscape(event)) {
                            coordinator.close(session.key)
                            true
                        } else {
                            false
                        }
                    },
                ) {
                    LaunchedEffect(session.focusRequestToken) {
                        window.toFront()
                        window.requestFocus()
                    }
                    ProvidePluginTextResolverV1 {
                        FlareTheme(isDarkTheme = true) {
                            ProvideComposeWindow {
                                DesktopMangaReaderWindowBody(
                                    session = session,
                                    coordinator = coordinator,
                                )
                            }
                            PlatformTitleBar {
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DesktopMangaReaderWindowBody(
    session: DesktopMangaReaderWindowSession,
    coordinator: DesktopMangaReaderWindowCoordinator,
    content: @Composable () -> Unit = {
        MangaReaderScreen(
            mangaKey = session.mangaKey,
            chapterId = session.chapterId,
            initialPage = session.initialPage,
            accountType = session.accountType,
            requestNonce = session.requestNonce,
            isStandaloneWindow = true,
            onBack = { coordinator.close(session.key) },
        )
    },
) {
    val systemUriHandler = LocalUriHandler.current
    val readerUriHandler =
        remember(systemUriHandler) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    val parsed = if (DeeplinkRoute.isDeeplink(uri)) DeeplinkRoute.parse(uri) else null
                    when (parsed) {
                        is DeeplinkRoute.OpenLinkDirectly -> systemUriHandler.openUri(parsed.url)
                        else -> systemUriHandler.openUri(uri)
                    }
                }
            }
        }
    CompositionLocalProvider(LocalUriHandler provides readerUriHandler) {
        ProvideDesktopSessionChallengeHost {
            content()
        }
    }
}
