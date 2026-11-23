package dev.dimension.flare.ui.presenter.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.dimension.flare.common.deeplink.ResolvedExternalDeepLink
import dev.dimension.flare.common.deeplink.resolveExternalDeepLink
import dev.dimension.flare.data.database.cache.model.TranslationDisplayMode
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.datastore.model.methodFor
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.SettingsRepository
import dev.dimension.flare.data.repository.accountServiceFlow
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.ui.model.DeeplinkEvent
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

public class DeepLinkPresenter(
    private val onRoute: (DeeplinkRoute) -> Unit,
    private val onLink: (String) -> Unit,
) : PresenterBase<DeepLinkPresenter.State>() {
    private val accountRepository: AccountRepository by koinInject()
    private val preTranslationService: PreTranslationService by koinInject()
    private val platformRegistry: PlatformRegistry by koinInject()
    private val settingsRepository: SettingsRepository by koinInject()

    @androidx.compose.runtime.Immutable
    public interface State {
        public fun handle(url: String)
    }

    private val accountPlatformsFlow by lazy {
        accountRepository.allAccounts.map {
            it
                .mapNotNull { account ->
                    platformRegistry
                        .get(account.platformId)
                        ?.let { spec -> account to spec }
                }.toMap()
                .toImmutableMap()
        }
    }

    @Composable
    override fun body(): State {
        var pendingUrl by remember { mutableStateOf<String?>(null) }
        pendingUrl?.let { url ->
            LaunchedEffect(url) {
                if (DeeplinkEvent.isDeeplinkEvent(url)) {
                    val event = DeeplinkEvent.parse(url)
                    if (event != null) {
                        when {
                            event.postEvent != null -> {
                                accountServiceFlow(
                                    accountType = AccountType.Specific(event.accountKey),
                                    repository = accountRepository,
                                ).firstOrNull()?.let { service ->
                                    service.capabilities.post
                                        ?.postEventHandler
                                        ?.handleEvent(event.postEvent)
                                }
                            }

                            event.translationEvent is DeeplinkEvent.TranslationEvent.RetryTranslation -> {
                                with(event.translationEvent) {
                                    preTranslationService.setStatusDisplayMode(
                                        accountType = AccountType.Specific(event.accountKey),
                                        statusKey = statusKey,
                                        mode = TranslationDisplayMode.Translated,
                                    )
                                    preTranslationService.retryStatus(
                                        accountType = AccountType.Specific(event.accountKey),
                                        statusKey = statusKey,
                                    )
                                }
                            }

                            event.translationEvent is DeeplinkEvent.TranslationEvent.Translate -> {
                                with(event.translationEvent) {
                                    preTranslationService.setStatusDisplayMode(
                                        accountType = AccountType.Specific(event.accountKey),
                                        statusKey = statusKey,
                                        mode = TranslationDisplayMode.Translated,
                                    )
                                    preTranslationService.retryStatus(
                                        accountType = AccountType.Specific(event.accountKey),
                                        statusKey = statusKey,
                                    )
                                }
                            }

                            event.translationEvent is DeeplinkEvent.TranslationEvent.ShowOriginal -> {
                                preTranslationService.setStatusDisplayMode(
                                    accountType = AccountType.Specific(event.accountKey),
                                    statusKey = event.translationEvent.statusKey,
                                    mode = TranslationDisplayMode.Original,
                                )
                            }
                        }
                    }
                    pendingUrl = null
                } else if (DeeplinkRoute.isDeeplink(url)) {
                    DeeplinkRoute.parse(url)?.let {
                        when (it) {
                            is DeeplinkRoute.OpenLinkDirectly -> {
                                withContext(Dispatchers.Main) {
                                    onLink(it.url)
                                }
                            }

                            else -> {
                                withContext(Dispatchers.Main) {
                                    onRoute(it)
                                }
                            }
                        }
                    }
                    pendingUrl = null
                } else {
                    val result =
                        resolveExternalDeepLink(url, accountPlatformsFlow.firstOrNull().orEmpty()) { host ->
                            settingsRepository
                                .appSettings
                                .firstOrNull()
                                ?.linkOpenDefaults
                                ?.methodFor(host)
                        }
                    withContext(Dispatchers.Main) {
                        if (pendingUrl != url) return@withContext
                        pendingUrl = null
                        when (result) {
                            is ResolvedExternalDeepLink.Navigate -> onRoute(result.route)
                            is ResolvedExternalDeepLink.Browser -> onLink(result.url)
                        }
                    }
                }
            }
        }

        return object : State {
            override fun handle(url: String) {
                pendingUrl = url
            }
        }
    }
}
