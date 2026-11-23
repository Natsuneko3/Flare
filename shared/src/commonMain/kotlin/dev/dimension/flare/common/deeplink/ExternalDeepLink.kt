package dev.dimension.flare.common.deeplink

import dev.dimension.flare.data.datastore.model.LinkOpenDefaultMethod
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.route.DeeplinkRoute
import io.ktor.http.Url
import kotlinx.collections.immutable.toImmutableMap

internal sealed interface ResolvedExternalDeepLink {
    data class Navigate(
        val route: DeeplinkRoute,
    ) : ResolvedExternalDeepLink

    data class Browser(
        val url: String,
    ) : ResolvedExternalDeepLink
}

internal fun PlatformSpec.linkOpenDefaultHosts(accountKey: MicroBlogKey): Set<String> =
    buildSet {
        deepLinks(accountKey).mapNotNullTo(this) { deepLink ->
            Url(deepLink.uriPattern).host.lowercase().takeIf { host -> host.isNotBlank() && '{' !in host && '}' !in host }
        }
        shortLink?.host?.let { add(it.lowercase()) }
    }

internal suspend fun resolveExternalDeepLink(
    url: String,
    accountPlatforms: Map<UiAccount, PlatformSpec>,
    defaultForHost: suspend (String) -> LinkOpenDefaultMethod?,
): ResolvedExternalDeepLink {
    val browser = ResolvedExternalDeepLink.Browser(url)
    if (accountPlatforms.isEmpty()) return browser
    val original = runCatching { Url(url) }.getOrNull() ?: return browser
    val originalHost = original.host.lowercase()
    val deepLinks = accountPlatforms.mapValues { (account, spec) -> spec.deepLinks(account.accountKey) }
    val directMatches = PlatformDeepLinkMatcher.matches(url, deepLinks)
    val shortLinkPlatform =
        if (directMatches.isEmpty()) {
            accountPlatforms.values.firstOrNull { it.shortLink?.host?.lowercase() == originalHost }
        } else {
            null
        }
    val shortLink = shortLinkPlatform?.shortLink
    if (directMatches.isEmpty() && shortLink == null) return browser
    val defaultMethod = defaultForHost(originalHost)
    if (defaultMethod is LinkOpenDefaultMethod.Browser) return browser

    val matches =
        if (shortLink == null) {
            directMatches
        } else {
            val resolvedUrl = shortLink.resolve(url) ?: return browser
            PlatformDeepLinkMatcher.matches(resolvedUrl, deepLinks.filterKeys { accountPlatforms[it] == shortLinkPlatform })
        }
    if (matches.isEmpty()) return browser

    val accountKey = (defaultMethod as? LinkOpenDefaultMethod.Account)?.accountKey
    val route =
        matches.entries
            .firstOrNull { it.key.accountKey == accountKey }
            ?.value
            ?.route
            ?: DeeplinkRoute.DeepLinkAccountPicker(
                originalUrl = url,
                data = matches.map { (account, match) -> account.accountKey to match.route }.toMap().toImmutableMap(),
            )
    return ResolvedExternalDeepLink.Navigate(route)
}
