package dev.dimension.flare.ui.route

import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import org.junit.Assert.assertEquals
import org.junit.Test

class MangaRouteTest {
    @Test
    fun mapsMangaDetailAndReaderDeeplinksForSpecificAndGuestPluginAccounts() {
        val mangaKey = MicroBlogKey("manga-99", "manga.example")
        val accounts =
            listOf(
                AccountType.Specific(MicroBlogKey("user-1", "manga.example")),
                AccountType.GuestPlugin(platformId = "manga-plat", host = "manga.example"),
            )

        for (accountType in accounts) {
            val detail =
                DeeplinkRoute.Manga.Detail(
                    mangaKey = mangaKey,
                    accountType = accountType,
                )
            assertEquals(
                Route.Manga.Detail(
                    mangaKey = mangaKey,
                    accountType = accountType,
                ),
                Route.parse(detail.toUri()),
            )

            val reader =
                DeeplinkRoute.Manga.Reader(
                    mangaKey = mangaKey,
                    chapterId = "ch-12",
                    initialPage = 5,
                    accountType = accountType,
                )
            assertEquals(
                Route.Manga.Reader(
                    mangaKey = mangaKey,
                    chapterId = "ch-12",
                    initialPage = 5,
                    accountType = accountType,
                ),
                Route.parse(reader.toUri()),
            )
        }
    }
}
