package dev.dimension.flare.ui.presenter.manga

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.dimension.flare.common.Locale
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.datastore.model.AppSettings
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.data.network.ai.AiCompletionService
import dev.dimension.flare.data.translation.AiPlaceholderTranslationSupport
import dev.dimension.flare.data.translation.PreTranslationContentRules
import dev.dimension.flare.data.translation.TranslationPromptFormatter
import dev.dimension.flare.data.translation.TranslationProvider
import dev.dimension.flare.data.translation.TranslationResponseSanitizer
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.ui.model.UiMangaDetail
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.withProxy
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.ui.render.RenderContent
import dev.dimension.flare.ui.render.applyTranslationJson
import dev.dimension.flare.ui.render.plainText
import dev.dimension.flare.ui.render.toTranslationDocument
import dev.dimension.flare.ui.render.toTranslationJson
import dev.dimension.flare.ui.render.toUiPlainText
import dev.dimension.flare.ui.render.uiRichTextOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

public class MangaTranslatePresenter(
    private val source: UiMangaDetail,
    private val targetLanguage: String = Locale.language,
) : PresenterBase<MangaTranslatePresenter.State>() {
    private val appDataStore: AppDataStore by koinInject()
    private val aiCompletionService: AiCompletionService by koinInject()
    private var config: Flow<AppSettings.TranslateConfig>? = null
    private var translate: suspend (
        UiMangaDetail,
        String,
    ) -> UiMangaDetail = ::translateMangaDetail

    internal constructor(
        source: UiMangaDetail,
        targetLanguage: String,
        config: Flow<AppSettings.TranslateConfig>,
        translate: suspend (UiMangaDetail, String) -> UiMangaDetail,
    ) : this(source, targetLanguage) {
        this.config = config
        this.translate = translate
    }

    @Immutable
    public interface State {
        public val detail: UiMangaDetail
        public val showTranslation: Boolean
        public val isTranslating: Boolean
        public val error: Throwable?

        public fun toggleTranslation()

        public fun retry()
    }

    @Composable
    override fun body(): State {
        val settings by remember { config ?: appDataStore.appSettingsStore.data.map { it.translateConfig } }
            .collectAsState(AppSettings.TranslateConfig())
        var translationOverride by remember { mutableStateOf<Boolean?>(null) }
        var refreshKey by remember { mutableIntStateOf(0) }
        val autoTranslate =
            settings.preTranslate &&
                !PreTranslationContentRules.shouldSkipForMatchingSourceLanguage(source.sourceLanguages, targetLanguage) &&
                !PreTranslationContentRules.shouldSkipForExcludedSourceLanguage(
                    source.sourceLanguages,
                    settings.autoTranslateExcludedLanguages,
                )
        val showTranslation by rememberUpdatedState(translationOverride ?: autoTranslate)
        val result by key(source, targetLanguage, settings.provider, refreshKey) {
            produceState<UiState<UiMangaDetail>?>(null) {
                snapshotFlow { showTranslation }.first { it }
                value =
                    try {
                        val translated = translate(source, targetLanguage)
                        currentCoroutineContext().ensureActive()
                        UiState.Success(translated)
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        currentCoroutineContext().ensureActive()
                        UiState.Error(error)
                    }
            }
        }
        val error = (result as? UiState.Error)?.throwable
        val translated = (result as? UiState.Success)?.data.takeIf { showTranslation }
        val displayDetail =
            remember(source, translated, settings.showOriginalWithTranslation) {
                translated?.let {
                    if (settings.showOriginalWithTranslation) it.withOriginal(source) else it
                } ?: source
            }
        return object : State {
            override val detail = displayDetail
            override val showTranslation = showTranslation
            override val isTranslating = showTranslation && result == null
            override val error = error.takeIf { showTranslation }

            override fun toggleTranslation() {
                translationOverride = !showTranslation
                if (!showTranslation && error != null) refreshKey++
            }

            override fun retry() {
                translationOverride = true
                refreshKey++
            }
        }
    }

    private suspend fun translateMangaDetail(
        detail: UiMangaDetail,
        targetLanguage: String,
    ): UiMangaDetail {
        val settings = appDataStore.appSettingsStore.data.first()
        val fields =
            buildList {
                add(detail.title.toMangaRenderText())
                detail.content?.renderRuns?.let(::addAll)
            }
        val combined = uiRichTextOf(fields)
        val document = combined.toTranslationDocument(targetLanguage)
        if (document.blocks.isEmpty()) return detail
        val promptTemplate = AiPlaceholderTranslationSupport.buildPromptTemplate(document)
        val response =
            TranslationProvider.translateDocumentJson(
                settings = settings,
                aiCompletionService = aiCompletionService,
                sourceTemplate = promptTemplate,
                sourceJson = combined.toTranslationJson(targetLanguage),
                targetLanguage = targetLanguage,
                prompt = TranslationPromptFormatter.buildTranslatePrompt(settings, targetLanguage, promptTemplate),
            )
        val translatedRuns =
            combined
                .applyTranslationJson(
                    TranslationResponseSanitizer.clean(requireNotNull(response) { "Translation returned empty response" }),
                ).renderRuns
        val translatedTitle = (translatedRuns.first() as RenderContent.Text).plainText()
        val proxy = ProxyRef.forAccountType(detail.accountType)
        val translatedContent =
            detail.content?.let {
                uiRichTextOf(
                    renderRuns = translatedRuns.drop(1),
                    sourceLanguages = listOf(targetLanguage),
                ).withProxy(proxy)
            }
        return detail.copy(
            title = translatedTitle,
            content = translatedContent,
        )
    }
}

internal fun UiMangaDetail.withOriginal(original: UiMangaDetail): UiMangaDetail {
    val origContent = original.content
    val transContent = content
    return copy(
        title = bilingualMangaText(original.title, title),
        content =
            if (origContent != null && transContent != null && !origContent.isEmpty && origContent != transContent) {
                uiRichTextOf(
                    renderRuns = origContent.renderRuns + transContent.renderRuns,
                    raw = "${origContent.raw}\n\n${transContent.raw}",
                    innerText = "${origContent.innerText}\n\n${transContent.innerText}",
                )
            } else {
                transContent
            },
    )
}

private fun String.toMangaRenderText(): RenderContent.Text = toUiPlainText().renderRuns.single() as RenderContent.Text

private fun bilingualMangaText(
    original: String,
    translated: String,
): String = if (original.isNotBlank() && original != translated) "$original\n\n$translated" else translated
