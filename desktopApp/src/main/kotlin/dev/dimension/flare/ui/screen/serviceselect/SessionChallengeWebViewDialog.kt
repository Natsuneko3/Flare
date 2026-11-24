package dev.dimension.flare.ui.screen.serviceselect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import compose.icons.FontAwesomeIcons
import compose.icons.fontawesomeicons.Solid
import compose.icons.fontawesomeicons.solid.ArrowLeft
import compose.icons.fontawesomeicons.solid.CircleExclamation
import dev.dimension.flare.LocalWindowPadding
import dev.dimension.flare.Res
import dev.dimension.flare.common.DeeplinkHandler
import dev.dimension.flare.common.InAppNotification
import dev.dimension.flare.common.Message
import dev.dimension.flare.data.model.ProxyMode
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.ProxyNetwork
import dev.dimension.flare.data.repository.RequireReLoginException
import dev.dimension.flare.data.repository.SessionChallengeCheckResult
import dev.dimension.flare.data.repository.SessionChallengeCookieAttributes
import dev.dimension.flare.data.repository.SessionChallengeCookieProbe
import dev.dimension.flare.data.repository.SessionChallengeCookieSnapshot
import dev.dimension.flare.data.repository.SessionChallengeCookieValue
import dev.dimension.flare.data.repository.SessionChallengeCoordinator
import dev.dimension.flare.data.repository.SessionChallengeException
import dev.dimension.flare.data.repository.SessionChallengeHandle
import dev.dimension.flare.data.repository.SessionChallengeSeedCookie
import dev.dimension.flare.login_expired_relogin
import dev.dimension.flare.session_challenge_close
import dev.dimension.flare.session_challenge_desktop_proxy_warning
import dev.dimension.flare.session_challenge_desktop_seed_cookies_unsupported
import dev.dimension.flare.session_challenge_expired
import dev.dimension.flare.session_challenge_relogin_message
import dev.dimension.flare.session_challenge_relogin_title
import dev.dimension.flare.session_challenge_title
import dev.dimension.flare.ui.component.FAIcon
import dev.dimension.flare.ui.component.LocalSessionChallengeLauncher
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import dev.dimension.flare.ui.theme.screenHorizontalPadding
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.InfoBarSeverity
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.SubtleButton
import io.github.composefluent.component.Text
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.LoadingState
import io.github.kdroidfilter.webview.web.WebContent
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewState
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.wry.WebViewCookie
import io.github.kdroidfilter.webview.wry.WryWebViewPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.koin.mp.KoinPlatformTools
import java.net.URI
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

private val VALID_SAMESITE_VALUES = setOf("strict", "lax", "none")

internal data class DesktopChallengePolicyDecision(
    val blockedBySeedCookies: Boolean,
    val usesSystemProxyFallback: Boolean,
)

internal fun evaluateDesktopChallengePolicy(
    accountProxyMode: ProxyMode,
    seedCookies: List<SessionChallengeSeedCookie>,
): DesktopChallengePolicyDecision =
    DesktopChallengePolicyDecision(
        blockedBySeedCookies = seedCookies.isNotEmpty(),
        usesSystemProxyFallback = accountProxyMode != ProxyMode.SYSTEM,
    )

internal fun shouldAllowDesktopChallengeRequest(
    isForMainFrame: Boolean,
    url: String,
): Boolean = !isForMainFrame || url.challengeHttpsOriginOrNull() != null

internal fun collectDesktopProbeCookies(
    probes: List<SessionChallengeCookieProbe>,
    getCookiesForUrl: (String) -> List<WebViewCookie>,
): List<SessionChallengeCookieValue> =
    probes.flatMap { probe ->
        val expected = probe.cookies.mapTo(mutableSetOf()) { it.name }
        if (expected.isEmpty()) return@flatMap emptyList()
        getCookiesForUrl(probe.url)
            .filter { it.name in expected }
            .map { cookie ->
                SessionChallengeCookieValue(
                    sourceUrl = probe.url,
                    name = cookie.name,
                    value = cookie.value,
                    attributes =
                        SessionChallengeCookieAttributes(
                            domain = cookie.domain?.takeIf { it.length <= 253 && it.none(Char::isISOControl) },
                            path = cookie.path?.takeIf { it.startsWith('/') && it.length <= 8_192 && it.none(Char::isISOControl) },
                            expiresAt = cookie.expiresDateMs,
                            session = cookie.isSessionOnly,
                            secure = cookie.isSecure,
                            httpOnly = cookie.isHttpOnly,
                            sameSite =
                                cookie.sameSite
                                    ?.name
                                    ?.lowercase()
                                    ?.takeIf { it in VALID_SAMESITE_VALUES },
                        ),
                )
            }
    }

internal fun String.challengeHttpsOriginOrNull(): String? =
    runCatching {
        val uri = URI(this)
        val host = uri.host?.lowercase()
        require(uri.scheme.equals("https", ignoreCase = true) && !host.isNullOrBlank() && uri.rawUserInfo == null)
        val portSuffix = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        "https://$host$portSuffix"
    }.getOrNull()

private data class PendingDesktopChallenge(
    val exception: SessionChallengeException,
    val onCompleted: () -> Unit,
)

@Composable
internal fun ProvideDesktopSessionChallengeHost(
    onRelogin: (RequireReLoginException) -> Unit = { relogin ->
        val handled = DeeplinkHandler.handleDeeplink(DeeplinkRoute.Relogin(relogin.accountKey, relogin.platformId).toUri())
        if (!handled) {
            KoinPlatformTools
                .defaultContext()
                .getOrNull()
                ?.getOrNull<InAppNotification>()
                ?.onError(Message.LoginExpired, relogin)
        }
    },
    content: @Composable () -> Unit,
) {
    var activeChallenge by remember { mutableStateOf<PendingDesktopChallenge?>(null) }
    var pendingRelogin by remember { mutableStateOf<RequireReLoginException?>(null) }
    val launcher =
        remember {
            { exception: SessionChallengeException, onCompleted: () -> Unit ->
                pendingRelogin = null
                activeChallenge = PendingDesktopChallenge(exception = exception, onCompleted = onCompleted)
            }
        }

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalSessionChallengeLauncher provides launcher) {
            content()
        }

        val current = activeChallenge
        if (current != null) {
            val accountProxyMode by
                produceState<ProxyMode?>(
                    initialValue = null,
                    key1 = current.exception.accountKey,
                ) {
                    ProxyNetwork.changes.collectLatest {
                        value =
                            runCatching {
                                ProxyNetwork.snapshot(ProxyRef.forAccount(current.exception.accountKey)).connection.mode
                            }.getOrElse {
                                ProxyMode.SYSTEM
                            }
                    }
                }
            SessionChallengeWebViewDialog(
                exception = current.exception,
                accountProxyMode = accountProxyMode,
                onCompleted = {
                    activeChallenge = null
                    current.onCompleted()
                },
                onRequireRelogin = { relogin ->
                    activeChallenge = null
                    pendingRelogin = relogin
                },
                onDismiss = {
                    activeChallenge = null
                },
            )
        }
    }

    val relogin = pendingRelogin
    if (relogin != null) {
        ContentDialog(
            title = stringResource(Res.string.session_challenge_relogin_title),
            visible = true,
            content = {
                Text(text = stringResource(Res.string.session_challenge_relogin_message))
            },
            primaryButtonText = stringResource(Res.string.login_expired_relogin),
            secondaryButtonText = stringResource(Res.string.session_challenge_close),
            onButtonClick = { button ->
                pendingRelogin = null
                if (button == ContentDialogButton.Primary) {
                    onRelogin(relogin)
                }
            },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SessionChallengeWebViewDialog(
    exception: SessionChallengeException,
    accountProxyMode: ProxyMode?,
    onCompleted: () -> Unit,
    onRequireRelogin: (RequireReLoginException) -> Unit,
    onDismiss: () -> Unit,
    beginSession: suspend (String) -> SessionChallengeHandle? = { challengeId ->
        KoinPlatformTools
            .defaultContext()
            .getOrNull()
            ?.getOrNull<SessionChallengeCoordinator>()
            ?.beginSession(challengeId)
    },
    webViewContent: (
        @Composable (
            challengeUrl: String,
            onOriginChanged: (String) -> Unit,
            onNavigationFinished: () -> Unit,
            onUserAgentAvailable: (String) -> Unit,
            onCookieSnapshotProviderReady: (() -> SessionChallengeCookieSnapshot?) -> Unit,
        ) -> Unit
    )? = null,
) {
    val latestOnCompleted by rememberUpdatedState(onCompleted)
    val latestOnRequireRelogin by rememberUpdatedState(onRequireRelogin)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    val initialOrigin = remember(exception.challengeUrl) { exception.challengeUrl.challengeHttpsOriginOrNull() }
    var currentOrigin by remember(exception.challengeId) { mutableStateOf(initialOrigin.orEmpty()) }
    var sessionHandle by remember(exception.challengeId) { mutableStateOf<SessionChallengeHandle?>(null) }
    var pollingFinished by remember(exception.challengeId) { mutableStateOf(false) }
    var sessionCompleted by remember(exception.challengeId) { mutableStateOf(false) }
    var errorText by remember(exception.challengeId) { mutableStateOf<String?>(null) }
    var cachedUserAgent by remember(exception.challengeId) { mutableStateOf<String?>(null) }
    var snapshotProvider by remember(exception.challengeId) { mutableStateOf<(() -> SessionChallengeCookieSnapshot?)?>(null) }
    val checkTriggers = remember(exception.challengeId) { Channel<Unit>(Channel.CONFLATED) }

    val expiredErrorText = stringResource(Res.string.session_challenge_expired)

    val cancelAndDismiss = {
        if (!sessionCompleted) {
            sessionHandle?.cancel()
        }
        latestOnDismiss()
    }

    DisposableEffect(exception.challengeId) {
        onDispose {
            checkTriggers.close()
            if (!sessionCompleted) {
                sessionHandle?.close()
            }
        }
    }

    LaunchedEffect(exception.challengeId) {
        sessionHandle = null
        pollingFinished = false
        errorText = null
        val handle =
            try {
                beginSession(exception.challengeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RequireReLoginException) {
                sessionCompleted = true
                latestOnRequireRelogin(e)
                return@LaunchedEffect
            } catch (_: Throwable) {
                null
            }
        if (handle == null) {
            errorText = expiredErrorText
            return@LaunchedEffect
        }
        sessionHandle = handle
    }

    val handle = sessionHandle
    val policy =
        remember(handle, accountProxyMode) {
            if (handle != null && accountProxyMode != null) {
                evaluateDesktopChallengePolicy(accountProxyMode, handle.seedCookies)
            } else {
                null
            }
        }

    LaunchedEffect(handle, policy?.blockedBySeedCookies) {
        val activeHandle = handle ?: return@LaunchedEffect
        if (policy?.blockedBySeedCookies != false) return@LaunchedEffect
        coroutineScope {
            val ticker =
                launch {
                    while (isActive && !pollingFinished && !sessionCompleted) {
                        delay(2.seconds)
                        checkTriggers.trySend(Unit)
                    }
                }
            try {
                for (trigger in checkTriggers) {
                    if (pollingFinished || sessionCompleted) break
                    val provider = snapshotProvider ?: continue
                    val rawSnapshot = provider() ?: continue
                    val snapshot =
                        if (rawSnapshot.userAgent.isNullOrBlank() && !cachedUserAgent.isNullOrBlank()) {
                            rawSnapshot.copy(userAgent = cachedUserAgent)
                        } else {
                            rawSnapshot
                        }
                    when (
                        val result =
                            try {
                                activeHandle.check(snapshot)
                            } catch (e: CancellationException) {
                                if (!isActive) throw e
                                pollingFinished = true
                                errorText = expiredErrorText
                                break
                            } catch (e: RequireReLoginException) {
                                pollingFinished = true
                                sessionCompleted = true
                                activeHandle.close()
                                latestOnRequireRelogin(e)
                                break
                            } catch (e: Exception) {
                                pollingFinished = true
                                errorText = e.message?.takeIf { it.isNotBlank() } ?: expiredErrorText
                                break
                            }
                    ) {
                        SessionChallengeCheckResult.Busy,
                        is SessionChallengeCheckResult.AwaitingCookies,
                        SessionChallengeCheckResult.Pending,
                        -> {
                            // Continue waiting for the next navigation or timer tick.
                        }

                        SessionChallengeCheckResult.Completed -> {
                            pollingFinished = true
                            sessionCompleted = true
                            activeHandle.close()
                            latestOnCompleted()
                            break
                        }
                    }
                }
            } finally {
                ticker.cancel()
            }
        }
    }

    val isOriginDeviated = currentOrigin.isNotEmpty() && currentOrigin != initialOrigin

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(FluentTheme.colors.background.solid.base)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            cancelAndDismiss()
                            true
                        } else {
                            false
                        }
                    }.padding(LocalWindowPadding.current),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = screenHorizontalPadding, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubtleButton(onClick = cancelAndDismiss, iconOnly = true) {
                    FAIcon(
                        imageVector = FontAwesomeIcons.Solid.ArrowLeft,
                        contentDescription = stringResource(Res.string.session_challenge_close),
                    )
                }
                Text(
                    text = currentOrigin.ifEmpty { stringResource(Res.string.session_challenge_title) },
                    style = FluentTheme.typography.subtitle,
                    color =
                        if (isOriginDeviated) {
                            FluentTheme.colors.system.critical
                        } else {
                            FluentTheme.colors.text.text.primary
                        },
                    modifier =
                        Modifier.testTag(
                            if (isOriginDeviated) {
                                "session_challenge_origin_deviated"
                            } else {
                                "session_challenge_origin"
                            },
                        ),
                )
            }

            if (policy?.usesSystemProxyFallback == true) {
                InfoBar(
                    title = {},
                    message = {
                        Text(text = stringResource(Res.string.session_challenge_desktop_proxy_warning))
                    },
                    severity = InfoBarSeverity.Warning,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = screenHorizontalPadding, vertical = 4.dp)
                            .testTag("session_challenge_proxy_warning"),
                )
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    errorText != null -> {
                        ChallengeErrorView(
                            message = errorText.orEmpty(),
                            onClose = cancelAndDismiss,
                            modifier = Modifier.testTag("session_challenge_error_view"),
                        )
                    }

                    handle == null || policy == null -> {
                        ProgressRing()
                    }

                    policy.blockedBySeedCookies -> {
                        ChallengeErrorView(
                            message = stringResource(Res.string.session_challenge_desktop_seed_cookies_unsupported),
                            onClose = cancelAndDismiss,
                            modifier = Modifier.testTag("session_challenge_seed_cookies_unsupported"),
                        )
                    }

                    else -> {
                        if (webViewContent == null) {
                            DesktopChallengeWryWebView(
                                challengeUrl = handle.challengeUrl,
                                probesProvider = { handle.probes },
                                onOriginChanged = { origin -> currentOrigin = origin },
                                onNavigationFinished = { checkTriggers.trySend(Unit) },
                                onUserAgentAvailable = { ua -> cachedUserAgent = ua },
                                onCookieSnapshotProviderReady = { provider -> snapshotProvider = provider },
                            )
                        } else {
                            webViewContent(
                                handle.challengeUrl,
                                { origin -> currentOrigin = origin },
                                { checkTriggers.trySend(Unit) },
                                { ua -> cachedUserAgent = ua },
                                { provider -> snapshotProvider = provider },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChallengeErrorView(
    message: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FAIcon(
            imageVector = FontAwesomeIcons.Solid.CircleExclamation,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = message,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onClose) {
            Text(text = stringResource(Res.string.session_challenge_close))
        }
    }
}

@Composable
private fun DesktopChallengeWryWebView(
    challengeUrl: String,
    probesProvider: () -> List<SessionChallengeCookieProbe>,
    onOriginChanged: (String) -> Unit,
    onNavigationFinished: () -> Unit,
    onUserAgentAvailable: (String) -> Unit,
    onCookieSnapshotProviderReady: (() -> SessionChallengeCookieSnapshot?) -> Unit,
) {
    val latestOnOriginChanged by rememberUpdatedState(onOriginChanged)
    val latestOnNavigationFinished by rememberUpdatedState(onNavigationFinished)
    val latestOnUserAgentAvailable by rememberUpdatedState(onUserAgentAvailable)
    val latestOnSnapshotProviderReady by rememberUpdatedState(onCookieSnapshotProviderReady)
    val latestProbesProvider by rememberUpdatedState(probesProvider)

    val state =
        remember(challengeUrl) {
            WebViewState(WebContent.Url("about:blank")).apply {
                webSettings.desktopWebSettings.incognito = true
            }
        }
    val interceptor =
        remember {
            object : RequestInterceptor {
                override fun onInterceptUrlRequest(
                    request: WebRequest,
                    navigator: io.github.kdroidfilter.webview.web.WebViewNavigator,
                ): WebRequestInterceptResult =
                    if (shouldAllowDesktopChallengeRequest(request.isForMainFrame, request.url)) {
                        WebRequestInterceptResult.Allow
                    } else {
                        WebRequestInterceptResult.Reject
                    }
            }
        }
    val navigator = rememberWebViewNavigator(requestInterceptor = interceptor)
    var cachedUserAgent by remember(challengeUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(state.lastLoadedUrl) {
        state.lastLoadedUrl?.challengeHttpsOriginOrNull()?.let(latestOnOriginChanged)
    }

    LaunchedEffect(state.loadingState, state.lastLoadedUrl) {
        if (state.loadingState is LoadingState.Finished && state.lastLoadedUrl?.challengeHttpsOriginOrNull() != null) {
            val webView = state.webView?.nativeWebView
            if (webView != null && cachedUserAgent == null && webView.isReady()) {
                readDesktopChallengeUserAgent(webView)?.let {
                    cachedUserAgent = it
                    latestOnUserAgentAvailable(it)
                }
            }
            latestOnNavigationFinished()
        }
    }

    LaunchedEffect(state.webView, challengeUrl) {
        cachedUserAgent = null
        val nativeWebView = state.webView?.nativeWebView ?: return@LaunchedEffect
        while (!nativeWebView.isReady()) {
            delay(50)
        }
        nativeWebView.clearAllCookies()
        navigator.loadUrl(challengeUrl)
        readDesktopChallengeUserAgent(nativeWebView)?.let { ua ->
            cachedUserAgent = ua
            latestOnUserAgentAvailable(ua)
        }
        latestOnSnapshotProviderReady {
            val webView = state.webView?.nativeWebView ?: return@latestOnSnapshotProviderReady null
            val cookies =
                collectDesktopProbeCookies(latestProbesProvider()) { url ->
                    webView.getCookiesForUrl(url)
                }
            SessionChallengeCookieSnapshot(
                cookies = cookies,
                userAgent = cachedUserAgent,
            )
        }
    }

    val disposeWebView: () -> Unit = {
        state.webView?.nativeWebView?.clearAllCookies()
    }
    WebView(
        state = state,
        modifier = Modifier.fillMaxSize(),
        navigator = navigator,
        onDispose = disposeWebView,
    )
}

private suspend fun readDesktopChallengeUserAgent(webView: WryWebViewPanel): String? {
    if (!webView.isReady()) return null
    val raw =
        withTimeoutOrNull(1.seconds) {
            suspendCancellableCoroutine<String?> { continuation ->
                try {
                    webView.evaluateJavaScript("navigator.userAgent") { result ->
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                } catch (_: Throwable) {
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    return raw?.let(::parseDesktopJavaScriptString)
}
