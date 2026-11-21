package dev.dimension.flare.data.platform

import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDeepLink
import dev.dimension.flare.model.xqtHost
import dev.dimension.flare.model.xqtOldHost
import dev.dimension.flare.ui.route.DeeplinkRoute
import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class XqtPlatformSpecTest {
    @Test
    fun twitterPostDeepLinksUseStatusRoute() {
        val deepLinks = XqtPlatformSpec.deepLinks(MicroBlogKey(id = "1", host = xqtHost))

        assertEquals(
            "https://$xqtOldHost/{handle}/status/{id}",
            deepLinks[5].uriPattern,
        )
        assertEquals(
            "https://www.$xqtOldHost/{handle}/status/{id}",
            deepLinks[7].uriPattern,
        )
    }

    @Test
    fun fxEmbedDomainsRegisterOnlyPostLinksForNumericStatusIds() {
        val accountKey = MicroBlogKey("1", xqtHost)
        val aliases = setOf("fixupx.com", "fxtwitter.com")
        val links = XqtPlatformSpec.deepLinks(accountKey).filter { Url(it.uriPattern).host in aliases }
        assertEquals(aliases.map { "https://$it/{handle}/status/{id}" }.toSet(), links.map { it.uriPattern }.toSet())
        assertEquals(aliases.size, links.size)

        links.forEach { link ->
            assertEquals(
                DeeplinkRoute.Status.Detail(
                    accountType = AccountType.Specific(accountKey),
                    statusKey = MicroBlogKey("2090282338471346648", xqtHost),
                ),
                link.routeForPost("2090282338471346648"),
                link.uriPattern,
            )
            assertNull(link.routeForPost("123.mp4"), link.uriPattern)
            assertNull(link.routeForPost("abc"), link.uriPattern)
            assertNull(link.routeForPost("123", handle = ""), link.uriPattern)
        }
    }

    private fun <T> PlatformDeepLink<T>.routeForPost(
        id: String,
        handle: String = "_",
    ): DeeplinkRoute? {
        val data =
            Json.decodeFromJsonElement(
                serializer,
                buildJsonObject {
                    put("handle", handle)
                    put("id", id)
                },
            )
        return if (matcher(data)) callback(data) else null
    }
}
