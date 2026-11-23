package dev.dimension.flare.ui.presenter.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import dev.dimension.flare.common.Locale
import dev.dimension.flare.common.PlatformDispatchers
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.mapper.saveMissingStatuses
import dev.dimension.flare.data.database.cache.model.DbTranslation
import dev.dimension.flare.data.database.cache.model.TranslationDisplayOptions
import dev.dimension.flare.data.database.cache.model.TranslationEntityType
import dev.dimension.flare.data.database.cache.model.applyTranslation
import dev.dimension.flare.data.database.cache.model.statusTranslationEntityKey
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.TimelinePagingMapper
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.accountServiceFlow
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.data.translation.TranslationSettingsSupport
import dev.dimension.flare.data.translation.pluginTranslationServiceOrNull
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.ReferenceType
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asTimelinePostItem
import dev.dimension.flare.ui.model.bindMediaProxy
import dev.dimension.flare.ui.model.contentPostOrNull
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.render.UiRichText
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

public class StatusSharePresenter(
    private val accountType: AccountType,
    private val statusKey: MicroBlogKey,
) : PresenterBase<StatusSharePresenter.State>() {
    private val accountRepository: AccountRepository by koinInject()
    private val database: CacheDatabase by koinInject()
    private val appDataStore: AppDataStore by koinInject()
    private val preTranslationService: PreTranslationService by koinInject()

    @Composable
    override fun body(): State {
        val status = remember { StatusPresenter(accountType, statusKey) }.body().status
        val scope = rememberCoroutineScope()
        val session =
            remember {
                var source: dev.dimension.flare.data.datasource.microblog.MicroblogDataSource? = null
                StatusShareSession(rootKey = statusKey, childLoader = { source?.shareReplyChildren(it) }) {
                    accountServiceFlow(accountType, accountRepository).first().also { source = it }.shareReplies(statusKey)
                }
            }
        var retryGeneration by remember { mutableIntStateOf(0) }
        val translationState =
            rememberShareReplyTranslations(
                replies = session.replies,
                retryGeneration = retryGeneration,
                database = database,
                appDataStore = appDataStore,
                preTranslationService = preTranslationService,
            )
        val replies =
            remember(session.replies, translationState) {
                session.replies.applyShareTranslations(translationState)
            }
        val selectedReplies =
            remember(session.selectedReplies, translationState) {
                session.selectedReplies.applyShareTranslations(translationState)
            }
        return object : State {
            override val status = status
            override val replies = replies
            override val selectedReplies = selectedReplies
            override val expandableReplies = session.expandableReplies
            override val selecting = session.draftKeys != null
            override val selectedCount = session.draftKeys?.size ?: selectedReplies.size
            override val loading = session.loading
            override val error = session.error
            override val hasMore = session.hasMore
            private val keys = session.draftKeys.orEmpty()

            override fun isSelected(key: MicroBlogKey): Boolean = key in keys

            override fun selectReplies() {
                retryGeneration++
                session.beginSelection()
                if (!session.started) loadMore()
            }

            override fun toggleReply(key: MicroBlogKey) = session.toggle(key)

            override fun clearSelection() = session.clear()

            override fun cancelSelection() = session.cancel()

            override fun confirmSelection() = session.confirm()

            override fun loadMore() {
                scope.launch { session.loadMore() }
            }

            override fun loadReplyChildren(key: MicroBlogKey) {
                scope.launch { session.loadChildren(key) }
            }
        }
    }

    @Immutable
    public interface State : StatusState {
        public val replies: ImmutableList<UiTimelineV2>
        public val selectedReplies: ImmutableList<UiTimelineV2>
        public val expandableReplies: ImmutableList<MicroBlogKey>
        public val selecting: Boolean
        public val selectedCount: Int
        public val loading: Boolean
        public val error: Throwable?
        public val hasMore: Boolean

        public fun isSelected(key: MicroBlogKey): Boolean

        public fun selectReplies()

        public fun toggleReply(key: MicroBlogKey)

        public fun clearSelection()

        public fun cancelSelection()

        public fun confirmSelection()

        public fun loadMore()

        public fun loadReplyChildren(key: MicroBlogKey)
    }
}

internal class StatusShareSession(
    private val rootKey: MicroBlogKey? = null,
    private val childLoader: (UiTimelineV2) -> RemoteLoader<UiTimelineV2>? = { null },
    private val createLoader: suspend () -> RemoteLoader<UiTimelineV2>,
) {
    var replies by mutableStateOf(emptyList<UiTimelineV2>().toImmutableList())
        private set
    var selectedReplies by mutableStateOf(emptyList<UiTimelineV2>().toImmutableList())
        private set
    var draftKeys by mutableStateOf<Set<MicroBlogKey>?>(null)
        private set
    var expandableReplies by mutableStateOf(emptyList<MicroBlogKey>().toImmutableList())
        private set

    private class ChildPage(
        val loader: RemoteLoader<UiTimelineV2>,
    ) {
        var started = false
        var nextKey: String? = null
        var items: List<UiTimelineV2> = emptyList()
    }

    private val children = mutableMapOf<MicroBlogKey, ChildPage>()
    private var failedChild: MicroBlogKey? = null
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var hasMore by mutableStateOf(true)
        private set
    var started = false
        private set
    private var loader: RemoteLoader<UiTimelineV2>? = null
    private var nextKey: String? = null

    fun beginSelection() {
        draftKeys = selectedReplies.map { it.statusKey }.toSet()
    }

    fun toggle(key: MicroBlogKey) {
        val keys = draftKeys ?: return
        if (replies.none { it.statusKey == key }) return
        draftKeys = if (key in keys) keys - key else keys + key
    }

    fun clear() {
        if (draftKeys != null) draftKeys = emptySet()
    }

    fun cancel() {
        draftKeys = null
    }

    fun confirm() {
        val keys = draftKeys ?: return
        selectedReplies = replies.filter { it.statusKey in keys }.toImmutableList()
        draftKeys = null
    }

    suspend fun loadMore() {
        failedChild?.let {
            loadChildren(it)
            return
        }
        if (loading || !hasMore) return
        loading = true
        error = null
        try {
            val source = loader ?: createLoader().also { loader = it }
            val initial = !started

            suspend fun loadPage() {
                val request = if (!started) PagingRequest.Refresh else PagingRequest.Append(requireNotNull(nextKey))
                val result = source.load(pageSize = 20, request = request)
                result.continuationFailure?.let { throw it }
                result.sourceFailures.firstOrNull()?.let { throw it }
                replies = (replies + result.data).distinctBy { it.statusKey }.toImmutableList()
                registerChildren(result.data)
                nextKey = result.nextKey
                hasMore = nextKey != null
                started = true
            }
            loadPage()
            // Detail sources fetch the focal post before their first reply page.
            if (initial && replies.isEmpty() && hasMore) loadPage()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            error = exception
        } finally {
            loading = false
        }
    }

    private fun registerChildren(items: List<UiTimelineV2>) {
        items.forEach { item ->
            if (item.statusKey !in children) {
                childLoader(item)?.let {
                    children[item.statusKey] = ChildPage(it)
                    expandableReplies = (expandableReplies + item.statusKey).toImmutableList()
                }
            }
        }
    }

    suspend fun loadChildren(key: MicroBlogKey) {
        val page = children[key] ?: return
        if (loading || (page.started && page.nextKey == null)) return
        loading = true
        error = null
        try {
            val request = if (page.started) PagingRequest.Append(requireNotNull(page.nextKey)) else PagingRequest.Refresh
            val result = page.loader.load(20, request)
            result.continuationFailure?.let { throw it }
            result.sourceFailures.firstOrNull()?.let { throw it }
            val parent = replies.first { it.statusKey == key }.contentPostOrNull()
            val newItems =
                result.data.filter { it.statusKey != rootKey && it.statusKey != key }.map { item ->
                    val post = item.asTimelinePostItem()
                    if (post == null || parent == null) {
                        item
                    } else {
                        post.copy(
                            post =
                                post.post.copy(
                                    replyToHandle = post.post.replyToHandle ?: parent.user?.handle?.display,
                                    references =
                                        (
                                            post.post.references +
                                                UiTimelineV2.Post.Reference(
                                                    key,
                                                    ReferenceType.Reply,
                                                )
                                        ).distinct().toImmutableList(),
                                ),
                        )
                    }
                }
            page.items = (page.items + newItems).distinctBy { it.statusKey }
            val keys = page.items.map { it.statusKey }.toSet()
            val remaining = replies.filter { it.statusKey !in keys }
            val index = remaining.indexOfFirst { it.statusKey == key } + 1
            replies = (remaining.take(index) + page.items + remaining.drop(index)).toImmutableList()
            page.started = true
            page.nextKey = result.nextKey
            failedChild = null
            if (page.nextKey == null) expandableReplies = expandableReplies.filter { it != key }.toImmutableList()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            failedChild = key
            error = exception
        } finally {
            loading = false
        }
    }
}

public fun shareReplyAccessibilityLabel(
    reply: UiTimelineV2,
    showOriginalWithTranslation: Boolean,
    expandContentWarning: Boolean,
): String {
    val item = reply.asTimelinePostItem() ?: return reply.searchText.orEmpty()
    return buildList {
        fun addText(text: UiRichText?) {
            val plainText = text?.innerText.orEmpty().trim()
            if (plainText.isNotEmpty()) add(plainText)
        }

        fun addPost(post: UiTimelineV2.Post) {
            addText(post.user?.name)
            val translated = post.translationDisplayState == TranslationDisplayState.Translated
            val warnings = post.contentWarning?.visiblePostText(translated, showOriginalWithTranslation).orEmpty()
            warnings.forEach(::addText)
            if (expandContentWarning || warnings.all { it.isEmpty }) {
                post.content.visiblePostText(translated, showOriginalWithTranslation).forEach(::addText)
            }
        }

        item.presentation.inlineParents.forEach { parent ->
            addPost(parent.displayPost)
            parent.presentation.quotes.forEach(::addPost)
        }
        addPost(item.displayPost)
        item.presentation.quotes.forEach(::addPost)
    }.joinToString("\n")
}

private const val SHARE_TRANSLATION_SQL_BATCH_SIZE = 500

private data class ShareReplyTranslationConfig(
    val displayOptions: TranslationDisplayOptions,
    val autoTranslateExcludedLanguages: List<String>,
    val appLanguage: String,
)

private data class ShareReplyTranslationState(
    val options: TranslationDisplayOptions,
    val translationsByEntityKey: Map<String, List<DbTranslation>>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
private fun rememberShareReplyTranslations(
    replies: ImmutableList<UiTimelineV2>,
    retryGeneration: Int,
    database: CacheDatabase,
    appDataStore: AppDataStore,
    preTranslationService: PreTranslationService,
): ShareReplyTranslationState? {
    val config by remember(appDataStore) {
        combine(
            appDataStore.appSettingsStore.data,
            pluginTranslationServiceOrNull()?.providers ?: flowOf(emptyList()),
        ) { appSettings, _ ->
            ShareReplyTranslationConfig(
                displayOptions = TranslationSettingsSupport.displayOptions(appSettings),
                autoTranslateExcludedLanguages = appSettings.translateConfig.autoTranslateExcludedLanguages,
                appLanguage = appSettings.language,
            )
        }.distinctUntilChanged()
    }.collectAsState(null)

    val targetLanguage = Locale.language
    val itemsByEntityKey = remember(replies) { replies.indexShareStatusItems() }
    val entityKeys = remember(itemsByEntityKey) { itemsByEntityKey.keys.toList() }
    val enqueuedIds = remember(config, targetLanguage, retryGeneration) { mutableSetOf<String>() }

    LaunchedEffect(replies, config, targetLanguage, retryGeneration) {
        val current = config ?: return@LaunchedEffect
        if (!current.displayOptions.autoDisplayEnabled || replies.isEmpty()) return@LaunchedEffect
        val alreadyEnqueued = enqueuedIds.toSet()
        try {
            val statuses =
                withContext(PlatformDispatchers.IO) {
                    val pending =
                        itemsByEntityKey
                            .filterKeys { it !in alreadyEnqueued }
                            .values
                            .map(TimelinePagingMapper::toDbStatus)
                    if (pending.isNotEmpty()) {
                        saveMissingStatuses(database, pending)
                    }
                    pending.map { it.status.data }
                }
            if (statuses.isEmpty()) return@LaunchedEffect
            statuses.chunked(SHARE_TRANSLATION_SQL_BATCH_SIZE).forEach { batch ->
                preTranslationService.enqueueStatuses(batch, allowLongText = true)
            }
            enqueuedIds += statuses.map { it.id }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            exception.printStackTrace()
        }
    }

    val currentConfig = config ?: return null
    val translationsByEntityKey by remember(database, entityKeys, currentConfig.appLanguage, targetLanguage) {
        if (entityKeys.isEmpty()) {
            flowOf(emptyMap())
        } else {
            database
                .invalidationTracker
                .createFlow("DbTranslation", emitInitialState = true)
                .mapLatest {
                    withContext(PlatformDispatchers.IO) {
                        loadTranslationsByEntityKey(
                            database = database,
                            entityKeys = entityKeys,
                            targetLanguage = targetLanguage,
                        )
                    }
                }.distinctUntilChanged()
        }
    }.collectAsState(emptyMap())

    return remember(currentConfig.displayOptions, translationsByEntityKey) {
        ShareReplyTranslationState(
            options = currentConfig.displayOptions,
            translationsByEntityKey = translationsByEntityKey,
        )
    }
}

private fun ImmutableList<UiTimelineV2>.applyShareTranslations(state: ShareReplyTranslationState?): ImmutableList<UiTimelineV2> {
    if (state == null || isEmpty()) return this
    var changed = false
    val mapped =
        map { item ->
            val translated =
                item.applyTranslation(state.options) { accountType, statusKey ->
                    state.translationsByEntityKey[statusTranslationEntityKey(accountType, statusKey)].orEmpty()
                }
            if (translated !== item) {
                changed = true
                translated.bindMediaProxy()
            } else {
                item
            }
        }
    return if (changed) mapped.toImmutableList() else this
}

private fun List<UiTimelineV2>.indexShareStatusItems(): Map<String, UiTimelineV2> =
    buildMap {
        fun index(item: UiTimelineV2) {
            getOrPut(statusTranslationEntityKey(item.accountType, item.statusKey)) { item }
        }

        fun collect(item: UiTimelineV2) {
            when (item) {
                is UiTimelineV2.TimelinePostItem -> {
                    index(item)
                    item.presentation.inlineParents.forEach(::collect)
                    item.presentation.quotes.forEach(::collect)
                    item.presentation.repost?.let(::collect)
                }

                is UiTimelineV2.Post,
                is UiTimelineV2.Feed,
                -> {
                    index(item)
                }

                is UiTimelineV2.UserList -> {
                    item.post?.let(::collect)
                }

                is UiTimelineV2.Gap,
                is UiTimelineV2.Message,
                is UiTimelineV2.User,
                -> {}
            }
        }
        forEach(::collect)
    }

private suspend fun loadTranslationsByEntityKey(
    database: CacheDatabase,
    entityKeys: List<String>,
    targetLanguage: String,
): Map<String, List<DbTranslation>> {
    if (entityKeys.isEmpty()) return emptyMap()
    return entityKeys
        .chunked(SHARE_TRANSLATION_SQL_BATCH_SIZE)
        .flatMap { keys ->
            database.translationDao().getByEntityKeys(
                entityType = TranslationEntityType.Status,
                entityKeys = keys,
                targetLanguage = targetLanguage,
            )
        }.groupBy { it.entityKey }
}
