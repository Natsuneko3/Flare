package dev.dimension.flare.common.deeplink

import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.datastore.model.LinkOpenDefaultMethod
import dev.dimension.flare.data.datastore.model.methodFor
import dev.dimension.flare.data.datastore.model.upsert
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDeepLink
import dev.dimension.flare.model.PlatformShortLink
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.testPlatformRegistry
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class ExternalDeepLinkTest {
    private val firstAccount = UiAccount(MicroBlogKey("1", "profiles.example"), "Profiles")
    private val secondAccount = UiAccount(MicroBlogKey("2", "profiles.example"), "Profiles")
    private val otherAccount = UiAccount(MicroBlogKey("3", "other.example"), "Other")
    private val shortUrl = "https://short.example/artist?ref=share#profile"
    private val profileUrl = "https://profiles.example/users/24431887"

    @Test
    fun shortLinkHostIsAvailableInLinkOpenDefaults() {
        val platform = profilePlatform(firstAccount.platformId, PlatformShortLink("Short.Example") { null })

        assertEquals(setOf("profiles.example", "short.example"), platform.linkOpenDefaultHosts(firstAccount.accountKey))
    }

    @Test
    fun resolvesOnceAndOffersOnlyAccountsForTheResolvingPlatform() =
        runTest {
            val requests = mutableListOf<String>()
            val platform =
                profilePlatform(
                    firstAccount.platformId,
                    PlatformShortLink("short.example") {
                        requests += it
                        profileUrl
                    },
                )
            val platforms =
                mapOf(
                    firstAccount to platform,
                    secondAccount to platform,
                    otherAccount to profilePlatform(otherAccount.platformId),
                )

            assertEquals(
                ResolvedExternalDeepLink.Navigate(
                    DeeplinkRoute.DeepLinkAccountPicker(
                        originalUrl = shortUrl,
                        data =
                            mapOf(
                                firstAccount.accountKey to profileRoute(firstAccount),
                                secondAccount.accountKey to profileRoute(secondAccount),
                            ),
                    ),
                ),
                resolveExternalDeepLink(shortUrl, platforms) { null },
            )
            assertEquals(listOf(shortUrl), requests)
        }

    @Test
    fun shortLinkUsesDefaultAccountForItsOriginalHost() =
        runTest {
            val platform = profilePlatform(secondAccount.platformId, PlatformShortLink("short.example") { profileUrl })
            val defaults =
                AppSettings
                    .LinkOpenDefaults()
                    .upsert("short.example", LinkOpenDefaultMethod.Account(secondAccount.accountKey))
                    .upsert("profiles.example", LinkOpenDefaultMethod.Browser)

            assertEquals(
                ResolvedExternalDeepLink.Navigate(profileRoute(secondAccount)),
                resolveExternalDeepLink("https://Short.Example/artist", mapOf(secondAccount to platform), defaults::methodFor),
            )
        }

    @Test
    fun shortLinkResolutionFailureOpensOriginalUrlInBrowser() =
        runTest {
            listOf(null, "https://profiles.example/about").forEach { targetUrl ->
                val platform = profilePlatform(firstAccount.platformId, PlatformShortLink("short.example") { targetUrl })
                assertEquals(
                    ResolvedExternalDeepLink.Browser(shortUrl),
                    resolveExternalDeepLink(shortUrl, mapOf(firstAccount to platform)) { null },
                )
            }
        }

    @Test
    fun shortLinkOpensInBrowserWhenItsPlatformHasNoAccount() =
        runTest {
            val platforms = mapOf(otherAccount to profilePlatform(otherAccount.platformId))

            assertEquals(
                ResolvedExternalDeepLink.Browser(shortUrl),
                resolveExternalDeepLink(shortUrl, platforms) { null },
            )
        }

    @Test
    fun browserPreferenceSkipsShortLinkResolution() =
        runTest {
            val platform =
                profilePlatform(
                    firstAccount.platformId,
                    PlatformShortLink("short.example") { fail("Short link resolution should be skipped") },
                )

            assertEquals(
                ResolvedExternalDeepLink.Browser(shortUrl),
                resolveExternalDeepLink(shortUrl, mapOf(firstAccount to platform)) { LinkOpenDefaultMethod.Browser },
            )
        }

    @Test
    fun directlyMatchedLinkUsesBrowserPreferenceForTheRequestedHost() =
        runTest {
            val url = profileUrl.replace("profiles.example", "Profiles.Example") + "?ref=share"
            val platform = profilePlatform(firstAccount.platformId)
            val lookedUpHosts = mutableListOf<String>()
            val defaults =
                AppSettings.LinkOpenDefaults(
                    rules = listOf(AppSettings.LinkOpenDefaults.Rule("Profiles.Example", AppSettings.LinkOpenDefaults.Method.Browser)),
                )

            assertEquals(
                ResolvedExternalDeepLink.Browser(url),
                resolveExternalDeepLink(url, mapOf(firstAccount to platform)) { host ->
                    lookedUpHosts += host
                    defaults.methodFor(host)
                },
            )
            assertEquals(listOf("profiles.example"), lookedUpHosts)
        }

    @Test
    fun directLinkMatchingAcceptsShareParametersAndRequiresTheRegisteredHostAndPath() =
        runTest {
            val platform = profilePlatform(firstAccount.platformId)
            val platforms = mapOf(firstAccount to platform)
            val method = LinkOpenDefaultMethod.Account(firstAccount.accountKey)

            assertEquals(
                ResolvedExternalDeepLink.Navigate(profileRoute(firstAccount)),
                resolveExternalDeepLink("$profileUrl?ref=share#profile", platforms) { method },
            )
            listOf(
                "https://profiles.example.evil.invalid/users/24431887",
                "https://prof\u0131les.example/users/24431887",
                "$profileUrl/bookmarks",
            ).forEach { url ->
                assertEquals(
                    ResolvedExternalDeepLink.Browser(url),
                    resolveExternalDeepLink(url, platforms) { method },
                )
            }
        }

    private fun profileRoute(account: UiAccount) =
        DeeplinkRoute.Profile.User(
            accountType = AccountType.Specific(account.accountKey),
            userKey = MicroBlogKey("24431887", "profiles.example"),
        )

    private fun profilePlatform(
        id: String,
        shortLinkRule: PlatformShortLink? = null,
    ): PlatformSpec =
        object : PlatformSpec by testPlatformRegistry().require("Mastodon") {
            override val platformId = id
            override val shortLink = shortLinkRule

            override fun deepLinks(accountKey: MicroBlogKey) =
                persistentListOf(
                    PlatformDeepLink(
                        uriPattern = "https://profiles.example/users/{id}",
                        serializer = ProfileId.serializer(),
                        callback = {
                            DeeplinkRoute.Profile.User(
                                accountType = AccountType.Specific(accountKey),
                                userKey = MicroBlogKey(it.id, "profiles.example"),
                            )
                        },
                    ),
                )
        }

    @Serializable
    private data class ProfileId(
        val id: String,
    )
}
