package dev.dimension.flare.ui.model

import dev.dimension.flare.common.Locale
import dev.dimension.flare.common.SerializableImmutableList
import dev.dimension.flare.common.decodeProtobuf
import dev.dimension.flare.common.encodeProtobuf
import dev.dimension.flare.data.database.cache.model.DbTranslation
import dev.dimension.flare.data.database.cache.model.TranslationDisplayOptions
import dev.dimension.flare.data.database.cache.model.TranslationEntityType
import dev.dimension.flare.data.database.cache.model.TranslationPayload
import dev.dimension.flare.data.database.cache.model.TranslationStatus
import dev.dimension.flare.data.database.cache.model.applyTranslation
import dev.dimension.flare.data.database.cache.model.sourceHash
import dev.dimension.flare.data.database.cache.model.translationPayload
import dev.dimension.flare.data.model.IconType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.render.UiRichText
import dev.dimension.flare.ui.render.toUiPlainText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UiProfileMergeTest {
    @Test
    fun `blank identity keeps richer profile data`() {
        val existing =
            createProfile(
                handle = "alice",
                name = "Alice",
            )
        val incomplete =
            createProfile(
                handle = "",
                name = "",
            )

        val merged = incomplete.mergeWith(existing)

        assertEquals("alice", merged.handle.raw)
        assertEquals("Alice", merged.name.raw)
    }

    @Test
    fun `merge keeps existing xqt location when newer profile omits it`() {
        val existing =
            createProfile(
                bottomContent =
                    UiProfile.BottomContent.Iconify(
                        items =
                            persistentMapOf(
                                UiProfile.BottomContent.Iconify.Icon.Location to "Tokyo".toUiPlainText(),
                                UiProfile.BottomContent.Iconify.Icon.Url to "example.com".toUiPlainText(),
                            ),
                    ),
            )
        val latest =
            createProfile(
                bottomContent =
                    UiProfile.BottomContent.Iconify(
                        items =
                            persistentMapOf(
                                UiProfile.BottomContent.Iconify.Icon.Url to "new.example.com".toUiPlainText(),
                            ),
                    ),
            )

        val merged = latest.mergeWith(existing)
        val bottomContent = assertIs<UiProfile.BottomContent.Iconify>(merged.bottomContent)

        assertEquals("Tokyo", bottomContent.items.getValue(UiProfile.BottomContent.Iconify.Icon.Location).raw)
        assertEquals("new.example.com", bottomContent.items.getValue(UiProfile.BottomContent.Iconify.Icon.Url).raw)
    }

    @Test
    fun `merge keeps existing bottom content when newer profile has none`() {
        val existing =
            createProfile(
                bottomContent =
                    UiProfile.BottomContent.Iconify(
                        items =
                            persistentMapOf(
                                UiProfile.BottomContent.Iconify.Icon.Location to "Tokyo".toUiPlainText(),
                            ),
                    ),
            )
        val latest = createProfile(bottomContent = null)

        val merged = latest.mergeWith(existing)
        val bottomContent = assertIs<UiProfile.BottomContent.Iconify>(merged.bottomContent)

        assertEquals("Tokyo", bottomContent.items.getValue(UiProfile.BottomContent.Iconify.Icon.Location).raw)
    }

    @Test
    fun `merge keeps existing url when newer profile omits it and overwrites with newer url`() {
        val existing = createProfile(url = "https://example.org/@alice")
        val partial = createProfile(url = null)
        val blank = createProfile(url = "   ")
        val updated = createProfile(url = "https://other.org/users/alice")

        assertEquals("https://example.org/@alice", partial.mergeWith(existing).url)
        assertEquals("https://example.org/@alice", blank.mergeWith(existing).url)
        assertEquals("https://other.org/users/alice", updated.mergeWith(existing).url)
    }

    @Test
    fun `protobuf decodes legacy UiProfile bytes without url field as null`() {
        val legacy =
            LegacyUiProfile(
                key = MicroBlogKey(id = "user", host = "x.com"),
                handle = UiHandle(raw = "alice", host = "x.com"),
                avatar = "https://example.com/avatar.jpg".toUiImage(),
                nameInternal = "Alice".toUiPlainText(),
                platformId = "xQt",
                clickEvent = ClickEvent.Noop,
                banner = null,
                description = "Bio".toUiPlainText(),
                matrices = UiProfile.Matrices(fansCount = 1, followsCount = 2, statusesCount = 3),
                mark = persistentListOf(),
                bottomContent = null,
                pluginId = "legacy-plugin",
                clearedMarks = persistentListOf(UiProfile.Mark.Verified),
            )

        val decoded = legacy.encodeProtobuf().decodeProtobuf<UiProfile>()

        assertNull(decoded.url)
        assertEquals(legacy.key, decoded.key)
        assertEquals(legacy.handle, decoded.handle)
        assertEquals(legacy.nameInternal.raw, decoded.name.raw)
        assertEquals("legacy-plugin", decoded.pluginId)
        assertEquals(persistentListOf(UiProfile.Mark.Verified), decoded.clearedMarks)
        assertEquals(
            "https://example.org/@alice",
            createProfile(url = "https://example.org/@alice").encodeProtobuf().decodeProtobuf<UiProfile>().url,
        )
    }

    @Test
    fun `applyTranslation preserves profile url`() {
        val profile =
            createProfile(url = "https://example.org/@alice").copy(
                description = "Hello world".toUiPlainText(),
            )
        val options =
            TranslationDisplayOptions(
                translationEnabled = true,
                autoDisplayEnabled = true,
                providerCacheKey = "ai",
            )
        val translation =
            DbTranslation(
                entityType = TranslationEntityType.Profile,
                entityKey = profile.key.toString(),
                targetLanguage = Locale.language,
                sourceHash = profile.translationPayload().sourceHash(options.providerCacheKey),
                status = TranslationStatus.Completed,
                payload = TranslationPayload(description = "Hola mundo".toUiPlainText()),
                updatedAt = 1,
            )

        val translated = profile.applyTranslation(options, translation)

        assertEquals(TranslationDisplayState.Translated, translated.translationDisplayState)
        assertEquals("Hola mundo", translated.description?.raw)
        assertEquals("https://example.org/@alice", translated.url)
    }

    @Serializable
    private data class LegacyUiProfile(
        val key: MicroBlogKey,
        val handle: UiHandle,
        val avatar: UiMedia.Image?,
        val nameInternal: UiRichText,
        @SerialName("platformType")
        val platformId: String,
        val platformIcon: IconType = UiIcon.World.asType(),
        val clickEvent: ClickEvent,
        val banner: UiMedia.Image?,
        val description: UiRichText?,
        val sourceLanguages: SerializableImmutableList<String> = persistentListOf(),
        @Transient
        val translationDisplayState: TranslationDisplayState = TranslationDisplayState.Hidden,
        val matrices: UiProfile.Matrices,
        val mark: SerializableImmutableList<UiProfile.Mark>,
        val bottomContent: UiProfile.BottomContent?,
        val pluginId: String? = null,
        val clearedMarks: SerializableImmutableList<UiProfile.Mark> = persistentListOf(),
    )

    private fun createProfile(
        bottomContent: UiProfile.BottomContent? = null,
        handle: String = "user",
        name: String = "User",
        url: String? = null,
    ): UiProfile =
        UiProfile(
            key = MicroBlogKey(id = "user", host = "x.com"),
            handle = UiHandle(raw = handle, host = "x.com"),
            avatar = "https://example.com/avatar.jpg",
            nameInternal = name.toUiPlainText(),
            platformId = "xQt",
            clickEvent = ClickEvent.Noop,
            banner = null,
            description = null,
            matrices = UiProfile.Matrices(fansCount = 1, followsCount = 2, statusesCount = 3),
            mark = persistentListOf(),
            bottomContent = bottomContent,
            url = url,
        )
}
