package dev.dimension.flare.ui.screen.manga

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.dimension.flare.ui.route.Route

internal fun EntryProviderScope<NavKey>.mangaEntryBuilder(
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    entry<Route.Manga.Detail> { args ->
        MangaDetailScreen(
            mangaKey = args.mangaKey,
            accountType = args.accountType,
            navigate = navigate,
            onBack = onBack,
        )
    }

    entry<Route.Manga.Reader> { args ->
        MangaReaderScreen(
            mangaKey = args.mangaKey,
            chapterId = args.chapterId,
            initialPage = args.initialPage,
            accountType = args.accountType,
            navigate = navigate,
            onBack = onBack,
        )
    }

    entry<Route.Manga.Comments> { args ->
        MangaCommentsScreen(
            mangaKey = args.mangaKey,
            accountType = args.accountType,
            onBack = onBack,
        )
    }
}
