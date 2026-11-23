package dev.dimension.flare.ui.presenter.status

import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.common.Locale
import dev.dimension.flare.common.OnDeviceAI
import dev.dimension.flare.common.TestFormatter
import dev.dimension.flare.common.decodeJson
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.app.model.DbAccount
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.model.DbStatus
import dev.dimension.flare.data.database.cache.model.DbTranslation
import dev.dimension.flare.data.database.cache.model.TranslationDisplayMode
import dev.dimension.flare.data.database.cache.model.TranslationEntityType
import dev.dimension.flare.data.database.cache.model.TranslationPayload
import dev.dimension.flare.data.database.cache.model.TranslationStatus
import dev.dimension.flare.data.database.cache.model.sourceHash
import dev.dimension.flare.data.database.cache.model.statusTranslationEntityKey
import dev.dimension.flare.data.database.cache.model.translationEntityKey
import dev.dimension.flare.data.database.cache.model.translationPayload
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.datasource.microblog.datasource.PostDataSource
import dev.dimension.flare.data.datasource.microblog.handler.PostEventHandler
import dev.dimension.flare.data.datasource.microblog.handler.PostHandler
import dev.dimension.flare.data.datasource.microblog.loader.PostLoader
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.TimelinePagingMapper
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.io.InMemoryFileStorage
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.data.network.ai.OpenAIService
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.DraftMediaStore
import dev.dimension.flare.data.repository.DraftRepository
import dev.dimension.flare.data.repository.ProxyRepository
import dev.dimension.flare.data.translation.AiPlaceholderTranslationSupport
import dev.dimension.flare.data.translation.OnlinePreTranslationService
import dev.dimension.flare.data.translation.PreTranslationBatchDocument
import dev.dimension.flare.data.translation.PreTranslationBatchItemStatus
import dev.dimension.flare.data.translation.PreTranslationBatchPayload
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.data.translation.TranslationSettingsSupport
import dev.dimension.flare.data.translation.aiPreTranslateConfig
import dev.dimension.flare.data.translation.translationProviderCacheKey
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.di.startKoin
import dev.dimension.flare.di.testSingle
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformRuntimeData
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.testPlatformRegistry
import dev.dimension.flare.ui.humanizer.PlatformFormatter
import dev.dimension.flare.ui.model.ClickEvent
import dev.dimension.flare.ui.model.TranslationDisplayState
import dev.dimension.flare.ui.model.UiHashtag
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.UiTranslatableText
import dev.dimension.flare.ui.model.asTimelinePostItem
import dev.dimension.flare.ui.model.contentPostOrNull
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.RenderRun
import dev.dimension.flare.ui.render.TranslationDocument
import dev.dimension.flare.ui.render.TranslationTokenKind
import dev.dimension.flare.ui.render.UiRichText
import dev.dimension.flare.ui.render.toUi
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StatusShareTranslationTest {
    private val accountKey = MicroBlogKey("me", "example.org")
    private val accountType = AccountType.Specific(accountKey)
    private val rootKey = MicroBlogKey("root", "example.org")
    private val foreignLanguage = if (Locale.language.startsWith("ja", ignoreCase = true)) "en" else "ja"

    @Test
    fun preTranslateEnabledTranslatesLoadedRepliesQuotesAndChildrenAndUpdatesConfirmedSelection() =
        withFixture {
            val rootPost = post("root", "Root post")
            val quoted = post("quoted", "Quoted reply text", sourceLanguage = foreignLanguage)
            val reply =
                UiTimelineV2.TimelinePostItem(
                    post("reply-1", "First reply text", sourceLanguage = foreignLanguage),
                    UiTimelineV2.PostPresentation(quotes = persistentListOf(quoted)),
                )
            val child = post("child-1", "Nested child reply", sourceLanguage = foreignLanguage)
            val gate = CompletableDeferred<Unit>()
            translationGate = gate
            val settings = configureTranslateSettings(preTranslate = true)
            insertAccount()
            rootStatus = rootPost
            replies = listOf(reply)
            childrenByParent = mapOf(reply.statusKey to listOf(child))

            val states = MutableStateFlow<StatusSharePresenter.State?>(null)
            val job = launchPresenter(states)
            try {
                val initial = states.filterNotNull().first()
                initial.selectReplies()
                val loaded = states.filterNotNull().first { it.replies.any { item -> item.statusKey == reply.statusKey } }
                loaded.toggleReply(reply.statusKey)
                states.filterNotNull().first { it.isSelected(reply.statusKey) }.confirmSelection()
                val confirmedBeforeTranslation =
                    states.filterNotNull().first { state ->
                        state.selectedReplies
                            .singleOrNull()
                            ?.contentPostOrNull()
                            ?.translationDisplayState == TranslationDisplayState.Translating
                    }
                assertEquals(reply.statusKey, confirmedBeforeTranslation.selectedReplies.single().statusKey)

                gate.complete(Unit)

                val translatedState =
                    states.filterNotNull().first { state ->
                        val selected = state.selectedReplies.singleOrNull()?.asTimelinePostItem()
                        selected?.post?.translationDisplayState == TranslationDisplayState.Translated &&
                            selected.presentation.quotes
                                .singleOrNull()
                                ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                val selectedItem = translatedState.selectedReplies.single().asTimelinePostItem()!!
                assertEquals(
                    "First reply text (${Locale.language})",
                    selectedItem.post.content.translation
                        ?.raw,
                )
                assertEquals(
                    "Quoted reply text (${Locale.language})",
                    selectedItem.presentation.quotes
                        .single()
                        .content.translation
                        ?.raw,
                )
                val cached = cache.pagingTimelineDao().searchCachedStatuses("%First reply text%", 10).single()
                val searchResult = TimelinePagingMapper.toUi(cached, "cache_search", TranslationSettingsSupport.displayOptions(settings))
                assertEquals(quoted.statusKey, searchResult.asTimelinePostItem()?.presentation?.quotes?.single()?.statusKey)

                translatedState.selectReplies()
                states.filterNotNull().first { it.selecting }.loadReplyChildren(reply.statusKey)
                val withChildTranslated =
                    states.filterNotNull().first { state ->
                        state.replies
                            .firstOrNull { it.statusKey == child.statusKey }
                            ?.contentPostOrNull()
                            ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(
                    "Nested child reply (${Locale.language})",
                    withChildTranslated.replies
                        .first { it.statusKey == child.statusKey }
                        .contentPostOrNull()
                        ?.content
                        ?.translation
                        ?.raw,
                )
            } finally {
                gate.complete(Unit)
                job.cancelAndJoin()
            }
        }

    @Test
    fun preTranslateDisabledHidesAutoTranslationsKeepsManualTranslationsAndReactsWhenEnabled() =
        withFixture {
            val rootPost = post("root", "Root post")
            val autoReply = post("auto-reply", "Auto translated reply", sourceLanguage = foreignLanguage)
            val manualReply = post("manual-reply", "Manual translated reply", sourceLanguage = foreignLanguage)
            val freshReply = post("fresh-reply", "Fresh untranslated reply", sourceLanguage = foreignLanguage)
            val settings = configureTranslateSettings(preTranslate = false)
            insertAccount()
            seedCachedTranslation(autoReply, "Cached auto", TranslationDisplayMode.Auto, settings)
            seedCachedTranslation(manualReply, "Cached manual", TranslationDisplayMode.Translated, settings)
            rootStatus = rootPost
            replies = listOf(autoReply, manualReply, freshReply)

            val states = MutableStateFlow<StatusSharePresenter.State?>(null)
            val job = launchPresenter(states)
            try {
                states.filterNotNull().first().selectReplies()
                val loaded =
                    states.filterNotNull().first { state ->
                        state.replies.size == 3 &&
                            state.replies
                                .first { it.statusKey == manualReply.statusKey }
                                .contentPostOrNull()
                                ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                delay(150)
                val latestDisabled = states.value!!
                assertEquals(0, translationCalls.get(), "preTranslate = false must not invoke the translator")
                val autoItem = latestDisabled.replies.first { it.statusKey == autoReply.statusKey }.contentPostOrNull()!!
                val manualItem = latestDisabled.replies.first { it.statusKey == manualReply.statusKey }.contentPostOrNull()!!
                val freshItem = latestDisabled.replies.first { it.statusKey == freshReply.statusKey }.contentPostOrNull()!!
                assertEquals(TranslationDisplayState.Hidden, autoItem.translationDisplayState)
                assertEquals(TranslationDisplayState.Translated, manualItem.translationDisplayState)
                assertEquals("Cached manual", manualItem.content.translation?.raw)
                assertEquals(TranslationDisplayState.Hidden, freshItem.translationDisplayState)
                assertNull(freshItem.content.translation)

                configureTranslateSettings(preTranslate = true)
                val enabledState =
                    states.filterNotNull().first { state ->
                        val auto = state.replies.firstOrNull { it.statusKey == autoReply.statusKey }?.contentPostOrNull()
                        val fresh = state.replies.firstOrNull { it.statusKey == freshReply.statusKey }?.contentPostOrNull()
                        auto?.translationDisplayState == TranslationDisplayState.Translated &&
                            fresh?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(
                    "Cached auto",
                    enabledState.replies
                        .first { it.statusKey == autoReply.statusKey }
                        .contentPostOrNull()
                        ?.content
                        ?.translation
                        ?.raw,
                )
                assertEquals(
                    "Fresh untranslated reply (${Locale.language})",
                    enabledState.replies
                        .first { it.statusKey == freshReply.statusKey }
                        .contentPostOrNull()
                        ?.content
                        ?.translation
                        ?.raw,
                )
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun preTranslateRespectsExcludedLanguagesAndPlatformTranslationPreference() =
        withFixture {
            val rootPost = post("root", "Root post")
            val excludedReply = post("excluded", "Excluded reply", sourceLanguage = foreignLanguage)
            val otherForeign = if (foreignLanguage == "ja") "fr" else "de"
            val platformReply =
                post("platform", "Platform reply", sourceLanguage = otherForeign).copy(
                    content =
                        UiTranslatableText(
                            original = "Platform reply".toUiPlainText(listOf(otherForeign)),
                            translation = "Platform native translation".toUiPlainText(listOf(Locale.language)),
                        ),
                )
            configureTranslateSettings(
                preTranslate = true,
                excludedLanguages = listOf(foreignLanguage),
                preferPlatformTranslation = true,
            )
            insertAccount()
            rootStatus = rootPost
            replies = listOf(excludedReply, platformReply)

            val states = MutableStateFlow<StatusSharePresenter.State?>(null)
            val job = launchPresenter(states)
            try {
                states.filterNotNull().first().selectReplies()
                val platformState =
                    states.filterNotNull().first { state ->
                        state.replies
                            .firstOrNull { it.statusKey == platformReply.statusKey }
                            ?.contentPostOrNull()
                            ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(0, translationCalls.get(), "Platform translation must be used without calling provider")
                assertEquals(
                    "Platform native translation",
                    platformState.replies
                        .first { it.statusKey == platformReply.statusKey }
                        .contentPostOrNull()
                        ?.content
                        ?.translation
                        ?.raw,
                )
                assertEquals(
                    TranslationDisplayState.Hidden,
                    platformState.replies
                        .first { it.statusKey == excludedReply.statusKey }
                        .contentPostOrNull()
                        ?.translationDisplayState,
                )

                configureTranslateSettings(
                    preTranslate = true,
                    excludedLanguages = listOf(foreignLanguage),
                    preferPlatformTranslation = false,
                )
                val providerState =
                    states.filterNotNull().first { state ->
                        state.replies
                            .firstOrNull { it.statusKey == platformReply.statusKey }
                            ?.contentPostOrNull()
                            ?.content
                            ?.translation
                            ?.raw == "Platform reply (${Locale.language})"
                    }
                assertEquals(
                    TranslationDisplayState.Translated,
                    providerState.replies
                        .first { it.statusKey == platformReply.statusKey }
                        .contentPostOrNull()
                        ?.translationDisplayState,
                )
                assertEquals(
                    TranslationDisplayState.Hidden,
                    providerState.replies
                        .first { it.statusKey == excludedReply.statusKey }
                        .contentPostOrNull()
                        ?.translationDisplayState,
                )

                configureTranslateSettings(
                    preTranslate = true,
                    excludedLanguages = emptyList(),
                    preferPlatformTranslation = false,
                )
                val unexcludedState =
                    states.filterNotNull().first { state ->
                        state.replies
                            .firstOrNull { it.statusKey == excludedReply.statusKey }
                            ?.contentPostOrNull()
                            ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(
                    "Excluded reply (${Locale.language})",
                    unexcludedState.replies
                        .first { it.statusKey == excludedReply.statusKey }
                        .contentPostOrNull()
                        ?.content
                        ?.translation
                        ?.raw,
                )
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun openingPickerKeepsManualTranslationForAnExcludedLanguage() =
        withFixture {
            val manual = post("manual", "Manually translated reply", sourceLanguage = foreignLanguage)
            val otherLanguage = if (Locale.language.startsWith("fr", ignoreCase = true)) "de" else "fr"
            val automatic = post("automatic", "Another reply", sourceLanguage = otherLanguage)
            val settings = configureTranslateSettings(preTranslate = true, excludedLanguages = listOf(foreignLanguage))
            insertAccount()
            seedCachedTranslation(manual, "Saved translation", TranslationDisplayMode.Translated, settings)
            val saved = cachedTranslation(manual)
            replies = listOf(manual, automatic)

            val states = MutableStateFlow<StatusSharePresenter.State?>(null)
            val job = launchPresenter(states)
            try {
                states.filterNotNull().first().selectReplies()
                val loaded =
                    states.filterNotNull().first {
                        it.replies.lastOrNull()?.contentPostOrNull()?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(saved, cachedTranslation(manual))
                assertEquals("Saved translation", loaded.replies.first().contentPostOrNull()?.content?.translation?.raw)
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun reopeningPickerKeepsTheTranslationOfAnEditedReply() =
        withFixture {
            val old = post("edited", "Old reply", sourceLanguage = foreignLanguage)
            val edited = post("edited", "Edited reply", sourceLanguage = foreignLanguage)
            val retry = post("retry", "Reply to retry", sourceLanguage = foreignLanguage)
            val settings = configureTranslateSettings(preTranslate = true)
            insertAccount()
            replies = listOf(old, retry)

            val states = MutableStateFlow<StatusSharePresenter.State?>(null)
            val job = launchPresenter(states)
            try {
                states.filterNotNull().first().selectReplies()
                states
                    .filterNotNull()
                    .first { state ->
                        state.replies.size == 2 &&
                            state.replies.all { it.contentPostOrNull()?.translationDisplayState == TranslationDisplayState.Translated }
                    }.cancelSelection()
                seedCachedTranslation(edited, "Translation of the edit", TranslationDisplayMode.Translated, settings)
                val saved = cachedTranslation(edited)
                cache.translationDao().insert(cachedTranslation(retry).copy(status = TranslationStatus.Failed, payload = null))

                states
                    .filterNotNull()
                    .first { !it.selecting && it.replies.last().contentPostOrNull()?.translationDisplayState == TranslationDisplayState.Failed }
                    .selectReplies()
                val reopened =
                    states.filterNotNull().first {
                        it.selecting && it.replies.last().contentPostOrNull()?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(saved, cachedTranslation(edited))
                assertEquals(TranslationDisplayState.Hidden, reopened.replies.first().contentPostOrNull()?.translationDisplayState)
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun translatedRepliesRebindMediaProxyForCustomEmojiAndBlockImages() =
        withFixture {
            val rootPost = post("root", "Root post")
            val richWithMedia =
                UiRichText(
                    renderRuns =
                        persistentListOf(
                            RenderContent.Text(
                                runs =
                                    persistentListOf(
                                        RenderRun.Text("Hello "),
                                        RenderRun.Image(
                                            url = "https://example.org/emoji.png",
                                            alt = ":wave:",
                                            proxy = ProxyRef.Account(accountKey),
                                        ),
                                    ),
                            ),
                            RenderContent.BlockImage(
                                url = "https://example.org/inline.png",
                                href = null,
                                proxy = ProxyRef.Account(accountKey),
                            ),
                        ),
                    isRtl = false,
                    raw = "Hello :wave:",
                    innerText = "Hello :wave:",
                    imageUrls = persistentListOf("https://example.org/inline.png"),
                )
            val reply =
                post("emoji-reply", "Hello :wave:", sourceLanguage = foreignLanguage).copy(
                    content = UiTranslatableText(original = richWithMedia),
                )
            configureTranslateSettings(preTranslate = true)
            insertAccount()
            rootStatus = rootPost
            replies = listOf(reply)

            val states = MutableStateFlow<StatusSharePresenter.State?>(null)
            val job = launchPresenter(states)
            try {
                states.filterNotNull().first().selectReplies()
                val translatedState =
                    states.filterNotNull().first { state ->
                        state.replies
                            .firstOrNull { it.statusKey == reply.statusKey }
                            ?.contentPostOrNull()
                            ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                val translatedRich =
                    translatedState.replies
                        .first { it.statusKey == reply.statusKey }
                        .contentPostOrNull()!!
                        .content
                        .translation!!
                val emojiRun =
                    translatedRich.renderRuns
                        .filterIsInstance<RenderContent.Text>()
                        .flatMap { it.runs }
                        .filterIsInstance<RenderRun.Image>()
                        .single()
                val blockImage = translatedRich.renderRuns.filterIsInstance<RenderContent.BlockImage>().single()
                assertEquals(ProxyRef.Account(accountKey), emojiRun.proxy)
                assertEquals(ProxyRef.Account(accountKey), blockImage.proxy)
            } finally {
                job.cancelAndJoin()
            }
        }

    @Test
    fun preTranslateKeepsManualOriginalHiddenAndTranslatesAppendedPagesOnlyOnce() =
        withFixture {
            val rootPost = post("root", "Root post")
            val originalOverride = post("override", "Keep original reply", sourceLanguage = foreignLanguage)
            val pageOneReply = post("page-1", "Page one reply", sourceLanguage = foreignLanguage)
            val pageTwoReply = post("page-2", "Page two reply", sourceLanguage = foreignLanguage)
            val settings = configureTranslateSettings(preTranslate = true)
            insertAccount()
            seedCachedTranslation(originalOverride, "Cached but hidden", TranslationDisplayMode.Original, settings)
            rootStatus = rootPost
            replies = listOf(originalOverride, pageOneReply)
            nextPageReplies = listOf(pageTwoReply)

            val states = MutableStateFlow<StatusSharePresenter.State?>(null)
            val job = launchPresenter(states)
            try {
                states.filterNotNull().first().selectReplies()
                val pageOneTranslated =
                    states.filterNotNull().first { state ->
                        state.replies.size == 2 &&
                            state.replies
                                .firstOrNull { it.statusKey == pageOneReply.statusKey }
                                ?.contentPostOrNull()
                                ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(
                    TranslationDisplayState.Hidden,
                    pageOneTranslated.replies
                        .first { it.statusKey == originalOverride.statusKey }
                        .contentPostOrNull()
                        ?.translationDisplayState,
                )
                assertEquals(1, translationCalls.get())

                pageOneTranslated.loadMore()
                val pageTwoTranslated =
                    states.filterNotNull().first { state ->
                        state.replies.size == 3 &&
                            state.replies
                                .firstOrNull { it.statusKey == pageTwoReply.statusKey }
                                ?.contentPostOrNull()
                                ?.translationDisplayState == TranslationDisplayState.Translated
                    }
                assertEquals(
                    "Page two reply (${Locale.language})",
                    pageTwoTranslated.replies
                        .first { it.statusKey == pageTwoReply.statusKey }
                        .contentPostOrNull()
                        ?.content
                        ?.translation
                        ?.raw,
                )
                assertEquals(2, translationCalls.get())
            } finally {
                job.cancelAndJoin()
            }
        }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        runBlocking {
            val main = Executors.newSingleThreadExecutor { Thread(it, "share-translation-ui") }.asCoroutineDispatcher()
            Dispatchers.setMain(main)
            val fixture = Fixture(CoroutineScope(main + SupervisorJob()))
            try {
                withContext(main) { withTimeout(15_000) { fixture.block() } }
            } finally {
                fixture.close()
                Dispatchers.resetMain()
                main.close()
            }
        }

    private inner class Fixture(
        val scope: CoroutineScope,
    ) {
        val root = createTestRootPath()
        val storage = OkioFileStorage(createTestFileSystem(), root)
        val app = Room.memoryDatabaseBuilder<AppDatabase>().setDriver(createDatabaseDriver()).build()
        val cache = Room.memoryDatabaseBuilder<CacheDatabase>().setDriver(createDatabaseDriver()).build()
        val appData = AppDataStore(storage)
        val translationCalls = AtomicInteger(0)
        var translationGate: CompletableDeferred<Unit>? = null
        var rootStatus: UiTimelineV2 = post("root", "Root")
        var replies: List<UiTimelineV2> = emptyList()
        var nextPageReplies: List<UiTimelineV2> = emptyList()
        var childrenByParent: Map<MicroBlogKey, List<UiTimelineV2>> = emptyMap()

        private val onDeviceAI =
            object : OnDeviceAI {
                override suspend fun isAvailable(): Boolean = true

                override suspend fun translate(
                    source: String,
                    targetLanguage: String,
                    prompt: String,
                ): String? {
                    translationCalls.incrementAndGet()
                    translationGate?.await()
                    val document = source.decodeJson(PreTranslationBatchDocument.serializer())
                    return AiPlaceholderTranslationSupport.buildPromptTemplate(
                        document.copy(
                            items =
                                document.items.map { item ->
                                    item.copy(
                                        status = PreTranslationBatchItemStatus.Completed,
                                        payload = requireNotNull(item.payload).translated(targetLanguage),
                                        reason = null,
                                    )
                                },
                        ),
                    )
                }

                override suspend fun tldr(
                    source: String,
                    targetLanguage: String,
                    prompt: String,
                ): String? = null
            }

        private val openAIService = OpenAIService()
        private val aiCompletionService = AiCompletionService(openAIService, onDeviceAI)
        private val preTranslationService: PreTranslationService =
            OnlinePreTranslationService(cache, appData, aiCompletionService, scope)

        val registry =
            PlatformRegistry(
                PlatformRuntimeData(
                    platformSpecs =
                        listOf(
                            object : PlatformSpec by testPlatformRegistry().defaultGuest {
                                override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource =
                                    ShareSource(AccountType.Specific(context.accountKey))

                                override fun guestDataSource(
                                    host: String,
                                    locale: String,
                                ): MicroblogDataSource = ShareSource(AccountType.Guest)
                            },
                        ),
                    extraTimelineSpecs = emptyList(),
                ),
            )

        val repository =
            AccountRepository(
                app,
                scope,
                appData,
                cache,
                ProxyRepository(storage),
                registry,
                DraftRepository(app, DraftMediaStore(InMemoryFileStorage())),
            )

        init {
            startKoin {
                modules(
                    module {
                        testSingle { app }
                        testSingle { cache }
                        testSingle { appData }
                        testSingle { repository }
                        testSingle { registry }
                        testSingle<CoroutineScope> { scope }
                        testSingle<OnDeviceAI> { onDeviceAI }
                        testSingle { openAIService }
                        testSingle { aiCompletionService }
                        testSingle<PreTranslationService> { preTranslationService }
                        testSingle<PlatformFormatter> { TestFormatter() }
                    },
                )
            }
        }

        suspend fun insertAccount() {
            app.accountDao().insert(
                DbAccount(accountKey, "{}", registry.defaultGuest.platformId, last_active = 1L, sort_id = 1L),
            )
        }

        suspend fun configureTranslateSettings(
            preTranslate: Boolean,
            excludedLanguages: List<String> = emptyList(),
            preferPlatformTranslation: Boolean = false,
        ): AppSettings =
            appData.appSettingsStore.updateData {
                it.copy(
                    language = Locale.language,
                    translateConfig =
                        aiPreTranslateConfig(preTranslate = preTranslate).copy(
                            autoTranslateExcludedLanguages = excludedLanguages,
                            preferPlatformTranslation = preferPlatformTranslation,
                        ),
                    aiConfig = AppSettings.AiConfig(type = AppSettings.AiConfig.Type.OnDevice),
                )
            }

        suspend fun cachedTranslation(post: UiTimelineV2.Post): DbTranslation =
            requireNotNull(
                cache.translationDao().get(
                    TranslationEntityType.Status,
                    statusTranslationEntityKey(post.accountType, post.statusKey),
                    Locale.language,
                ),
            )

        suspend fun seedCachedTranslation(
            post: UiTimelineV2.Post,
            translatedText: String,
            displayMode: TranslationDisplayMode,
            settings: AppSettings,
        ) {
            val dbStatus =
                DbStatus(
                    statusKey = post.statusKey,
                    accountType = accountType,
                    content = post,
                    renderHash = post.renderHash,
                    text = post.searchText,
                )
            cache.statusDao().insert(dbStatus)
            val sourcePayload = requireNotNull(post.translationPayload())
            cache.translationDao().insert(
                DbTranslation(
                    entityType = TranslationEntityType.Status,
                    entityKey = dbStatus.translationEntityKey(),
                    targetLanguage = Locale.language,
                    sourceHash = sourcePayload.sourceHash(settings.translationProviderCacheKey()),
                    status = TranslationStatus.Completed,
                    displayMode = displayMode,
                    payload = TranslationPayload(content = translatedText.toUiPlainText(listOf(Locale.language))),
                    attemptCount = 1,
                    updatedAt = 1L,
                ),
            )
        }

        fun launchPresenter(states: MutableStateFlow<StatusSharePresenter.State?>) =
            scope.launch {
                val presenter = StatusSharePresenter(accountType = accountType, statusKey = rootKey)
                moleculeFlow(RecompositionMode.Immediate) { presenter.body() }.collect { states.value = it }
            }

        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            app.close()
            cache.close()
            stopKoin()
            deleteTestRootPath(root)
        }

        private inner class ShareSource(
            accountType: AccountType,
        ) : MicroblogDataSource,
            PostDataSource {
            override val postEventHandler: PostEventHandler
                get() = error("Unused")

            override val postHandler: PostHandler =
                PostHandler(
                    accountType = accountType,
                    loader =
                        object : PostLoader {
                            override suspend fun status(statusKey: MicroBlogKey): UiTimelineV2 = rootStatus

                            override suspend fun deleteStatus(statusKey: MicroBlogKey) = Unit
                        },
                )

            override fun homeTimeline() = notSupported<UiTimelineV2>()

            override fun userTimeline(
                userKey: MicroBlogKey,
                mediaOnly: Boolean,
            ) = notSupported<UiTimelineV2>()

            override fun context(statusKey: MicroBlogKey): RemoteLoader<UiTimelineV2> =
                object : RemoteLoader<UiTimelineV2> {
                    override suspend fun load(
                        pageSize: Int,
                        request: PagingRequest,
                    ): PagingResult<UiTimelineV2> =
                        when (request) {
                            PagingRequest.Refresh -> {
                                PagingResult(
                                    data = replies,
                                    nextKey = if (nextPageReplies.isNotEmpty()) "page-2" else null,
                                )
                            }

                            is PagingRequest.Append -> {
                                PagingResult(data = nextPageReplies)
                            }

                            is PagingRequest.Prepend -> {
                                PagingResult()
                            }
                        }
                }

            override fun shareReplyChildren(reply: UiTimelineV2): RemoteLoader<UiTimelineV2>? =
                childrenByParent[reply.statusKey]?.let { childList ->
                    object : RemoteLoader<UiTimelineV2> {
                        override suspend fun load(
                            pageSize: Int,
                            request: PagingRequest,
                        ): PagingResult<UiTimelineV2> = PagingResult(data = childList)
                    }
                }

            override fun searchStatus(query: String) = notSupported<UiTimelineV2>()

            override fun searchUser(query: String) = notSupported<UiProfile>()

            override fun discoverUsers() = notSupported<UiProfile>()

            override fun discoverStatuses() = notSupported<UiTimelineV2>()

            override fun discoverHashtags() = notSupported<UiHashtag>()

            override fun following(userKey: MicroBlogKey) = notSupported<UiProfile>()

            override fun fans(userKey: MicroBlogKey) = notSupported<UiProfile>()

            override suspend fun profileTabs(userKey: MicroBlogKey) = persistentListOf<ProfileTab>()
        }
    }

    private fun post(
        id: String,
        text: String,
        sourceLanguage: String = Locale.language,
    ): UiTimelineV2.Post =
        UiTimelineV2.Post(
            platformId = "Mastodon",
            images = persistentListOf(),
            sensitive = false,
            contentWarning = null,
            user = null,
            sourceLanguages = persistentListOf(sourceLanguage),
            content = UiTranslatableText(text.toUiPlainText(listOf(sourceLanguage))),
            actions = persistentListOf(),
            poll = null,
            statusKey = MicroBlogKey(id, "example.org"),
            card = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z").toUi(),
            clickEvent = ClickEvent.Noop,
            accountType = accountType,
        )
}

private fun PreTranslationBatchPayload.translated(targetLanguage: String): PreTranslationBatchPayload =
    PreTranslationBatchPayload(
        content = content?.translated(targetLanguage),
        contentWarning = contentWarning?.translated(targetLanguage),
        title = title?.translated(targetLanguage),
        description = description?.translated(targetLanguage),
    )

private fun TranslationDocument.translated(targetLanguage: String): TranslationDocument =
    copy(
        blocks =
            blocks.map { block ->
                block.copy(
                    tokens =
                        block.tokens.map { token ->
                            if (token.kind == TranslationTokenKind.Translatable) {
                                token.copy(text = "${token.text} ($targetLanguage)")
                            } else {
                                token
                            }
                        },
                )
            },
    )
