package dev.dimension.flare.data.platform

import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDeepLink
import dev.dimension.flare.ui.route.DeeplinkRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PixivPlatformSpecTest {
    @Test
    fun registersArtworkAndProfileVariantsForEachPixivAccount() {
        for (account in listOf(MicroBlogKey("1", PIXIV_HOST), MicroBlogKey("2", PIXIV_HOST))) {
            val artwork =
                DeeplinkRoute.Gallery.Detail(
                    accountType = AccountType.Specific(account),
                    statusKey = MicroBlogKey("149594519", PIXIV_HOST),
                )
            val profile =
                DeeplinkRoute.Profile.User(
                    accountType = AccountType.Specific(account),
                    userKey = MicroBlogKey("12345", PIXIV_HOST),
                )
            val artworkPatterns = patternsFor("artworks/{id}")
            val profilePatterns = patternsFor("users/{id}")
            val links = PixivPlatformSpec.deepLinks(account)
            assertEquals(artworkPatterns + profilePatterns, links.map { it.uriPattern }.toSet())
            assertEquals(artworkPatterns.size + profilePatterns.size, links.size)
            links.forEach { link ->
                if (link.uriPattern in artworkPatterns) {
                    assertEquals(artwork, link.routeFor("149594519"), link.uriPattern)
                } else {
                    assertEquals(profile, link.routeFor("12345"), link.uriPattern)
                }
            }
        }
    }

    private fun patternsFor(path: String): Set<String> =
        setOf(
            "https://www.pixiv.net/$path",
            "https://www.pixiv.net/en/$path",
            "https://www.pixiv.net/$path/",
            "https://www.pixiv.net/en/$path/",
            "https://pixiv.net/$path",
            "https://pixiv.net/en/$path",
            "https://pixiv.net/$path/",
            "https://pixiv.net/en/$path/",
        )

    private fun <T> PlatformDeepLink<T>.routeFor(id: String): DeeplinkRoute {
        val data = Json.decodeFromJsonElement(serializer, buildJsonObject { put("id", id) })
        assertTrue(matcher(data), uriPattern)
        return callback(data)
    }
}
