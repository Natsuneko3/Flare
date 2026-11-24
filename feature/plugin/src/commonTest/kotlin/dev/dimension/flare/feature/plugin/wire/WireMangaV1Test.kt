package dev.dimension.flare.feature.plugin.wire

import dev.dimension.flare.feature.plugin.abi.PluginAbiV1
import dev.dimension.flare.feature.plugin.abi.PluginJsonV1
import dev.dimension.flare.feature.plugin.adapter.PluginSessionCapabilitiesV1
import dev.dimension.flare.feature.plugin.manifest.PluginManifestV1
import dev.dimension.flare.feature.plugin.manifest.socialPlatform
import dev.dimension.flare.feature.plugin.manifest.validate
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireMangaV1Test {
    @Test
    fun validatesMangaExclusivityChapterUniquenessAndPageMediaRules() {
        val baseManga = validManga()
        baseManga.requireValid(mediaNetwork = true)
        baseManga.copy(cover = null).requireValid(mediaNetwork = false)

        // Cover requires mediaNetwork and image media type
        assertFailsWith<IllegalArgumentException> {
            baseManga.requireValid(mediaNetwork = false)
        }
        assertFailsWith<IllegalArgumentException> {
            baseManga.copy(cover = validImageMedia().copy(type = MediaTypeV1.Gif)).requireValid(mediaNetwork = true)
        }

        // 1. Rejects both tags and tagGroups, or both chapters and chapterGroups
        val tag = MangaTagV1(name = "Action", target = NavigationTargetV1.Manga(EntityKeyV1("m-2", "manga.example")))
        val tagGroup = MangaTagGroupV1(id = "genre", title = WireTextV1(value = "Genre"), tags = listOf(tag))
        baseManga.copy(tags = listOf(tag), tagGroups = null).requireValid(mediaNetwork = true)
        baseManga.copy(tags = null, tagGroups = listOf(tagGroup)).requireValid(mediaNetwork = true)
        assertFailsWith<IllegalArgumentException> {
            baseManga.copy(tags = listOf(tag), tagGroups = listOf(tagGroup)).requireValid(mediaNetwork = true)
        }

        val ch1 = MangaChapterV1(id = "ch-1", title = "Chapter 1")
        val ch2 = MangaChapterV1(id = "ch-2", title = "Chapter 2")
        val groupA = MangaChapterGroupV1(id = "main", title = WireTextV1(value = "Serialized"), chapters = listOf(ch1))
        baseManga.copy(chapters = listOf(ch1, ch2), chapterGroups = null).requireValid(mediaNetwork = true)
        baseManga.copy(chapters = null, chapterGroups = listOf(groupA)).requireValid(mediaNetwork = true)
        assertFailsWith<IllegalArgumentException> {
            baseManga.copy(chapters = listOf(ch1), chapterGroups = listOf(groupA)).requireValid(mediaNetwork = true)
        }

        // 2. Rejects duplicate chapter.id across different chapterGroups and within flat chapters
        val groupBWithDup =
            MangaChapterGroupV1(
                id = "tankobon",
                title = WireTextV1(value = "Volumes"),
                chapters = listOf(ch1.copy(title = "Vol. 1")),
            )
        assertFailsWith<IllegalArgumentException> {
            baseManga.copy(chapters = null, chapterGroups = listOf(groupA, groupBWithDup)).requireValid(mediaNetwork = true)
        }
        assertFailsWith<IllegalArgumentException> {
            baseManga.copy(chapters = listOf(ch1, ch1.copy(title = "Duplicate")), chapterGroups = null).requireValid(mediaNetwork = true)
        }

        // Bounds checks: max 32 chapterGroups, max 64 tagGroups, max 200 tags per group
        assertFailsWith<IllegalArgumentException> {
            val tooManyGroups =
                (1..33).map { index ->
                    MangaChapterGroupV1(
                        id = "g-$index",
                        title = WireTextV1(value = "Group $index"),
                        chapters = listOf(MangaChapterV1(id = "c-$index", title = "Ch $index")),
                    )
                }
            baseManga.copy(chapters = null, chapterGroups = tooManyGroups).requireValid(mediaNetwork = true)
        }
        assertFailsWith<IllegalArgumentException> {
            val tooManyTagGroups =
                (1..65).map { index ->
                    MangaTagGroupV1(id = "tg-$index", title = WireTextV1(value = "Tag Group $index"), tags = listOf(tag))
                }
            baseManga.copy(tags = null, tagGroups = tooManyTagGroups).requireValid(mediaNetwork = true)
        }
        assertFailsWith<IllegalArgumentException> {
            val tooManyTags = (1..201).map { index -> MangaTagV1(name = "Tag $index") }
            baseManga.copy(tags = tooManyTags, tagGroups = null).requireValid(mediaNetwork = true)
        }

        // 3. Rejects locked chapter when both chapter.url and manga.url are absent
        val lockedWithoutUrl = MangaChapterV1(id = "ch-locked", title = "Locked Chapter", locked = true, url = null)
        assertFailsWith<IllegalArgumentException> {
            baseManga.copy(url = null, chapters = listOf(lockedWithoutUrl)).requireValid(mediaNetwork = true)
        }
        baseManga.copy(url = "https://manga.example/series/1", chapters = listOf(lockedWithoutUrl)).requireValid(mediaNetwork = true)
        baseManga
            .copy(
                url = null,
                chapters = listOf(lockedWithoutUrl.copy(url = "https://manga.example/series/1/ch-locked")),
            ).requireValid(mediaNetwork = true)

        // 4. MangaPageV1 accepts image/gif, rejects video/audio/ugoira, rejects pageToken when pageImageSupported = false
        val imagePage = MangaPageV1(id = "p-1", media = validImageMedia())
        val gifPage = MangaPageV1(id = "p-2", media = validImageMedia().copy(id = "m-gif", type = MediaTypeV1.Gif))
        MangaPagesV1(pages = listOf(imagePage, gifPage)).requireValid(mediaNetwork = true, pageImageSupported = false)

        assertFailsWith<IllegalArgumentException> {
            MangaPagesV1(pages = emptyList()).requireValid(mediaNetwork = true, pageImageSupported = false)
        }
        assertFailsWith<IllegalArgumentException> {
            MangaPagesV1(pages = listOf(imagePage)).requireValid(mediaNetwork = false, pageImageSupported = false)
        }
        listOf(MediaTypeV1.Video, MediaTypeV1.Audio, MediaTypeV1.Ugoira).forEach { forbiddenType ->
            val invalidMedia =
                validImageMedia().copy(
                    type = forbiddenType,
                    ugoiraKey = if (forbiddenType == MediaTypeV1.Ugoira) EntityKeyV1("u-1", "manga.example") else null,
                    previewUrl = if (forbiddenType == MediaTypeV1.Ugoira) "https://cdn.manga.example/p.jpg" else null,
                )
            assertFailsWith<IllegalArgumentException>("type=$forbiddenType") {
                MangaPagesV1(pages = listOf(MangaPageV1(id = "p-bad", media = invalidMedia)))
                    .requireValid(mediaNetwork = true, pageImageSupported = true)
            }
            assertFailsWith<IllegalArgumentException>("pageImage type=$forbiddenType") {
                invalidMedia.requireValidMangaPageMedia(mediaNetwork = true)
            }
        }

        val deferredPage =
            MangaPageV1(
                id = "p-deferred",
                pageToken = "tok-p1",
                previewUrl = "https://cdn.manga.example/thumb-1.jpg",
                width = 1200,
                height = 1800,
                customHeaders = mapOf("Referer" to "https://manga.example/"),
            )
        assertFailsWith<IllegalArgumentException> {
            MangaPagesV1(pages = listOf(deferredPage)).requireValid(mediaNetwork = true, pageImageSupported = false)
        }
        MangaPagesV1(pages = listOf(deferredPage)).requireValid(mediaNetwork = true, pageImageSupported = true)

        // Rejects providing both or neither of media and pageToken
        assertFailsWith<IllegalArgumentException> {
            MangaPageV1(id = "p-both", media = validImageMedia(), pageToken = "tok")
                .requireValid(mediaNetwork = true, pageImageSupported = true)
        }
        assertFailsWith<IllegalArgumentException> {
            MangaPageV1(id = "p-neither").requireValid(mediaNetwork = true, pageImageSupported = true)
        }

        // 5. Unknown status/readingDirection deserialize to Unknown without failing
        val unknownDecoded =
            PluginJsonV1.decodeFromString<MangaV1>(
                """
                {
                  "key": { "id": "m-1", "host": "manga.example" },
                  "title": "Sample Manga",
                  "status": "publishing_special",
                  "readingDirection": "bottom_to_top"
                }
                """.trimIndent(),
            )
        assertEquals(MangaStatusV1.Unknown, unknownDecoded.status)
        assertEquals(MangaReadingDirectionV1.Unknown, unknownDecoded.readingDirection)
        unknownDecoded.requireValid(mediaNetwork = false)

        // NavigationTargetV1 manga & mangaChapter round-trip and validation
        val mangaTarget: NavigationTargetV1 = NavigationTargetV1.Manga(EntityKeyV1("m-1", "manga.example"))
        val chapterTarget: NavigationTargetV1 = NavigationTargetV1.MangaChapter(EntityKeyV1("m-1", "manga.example"), "ch-42")
        mangaTarget.requireValid()
        chapterTarget.requireValid()
        val encodedChapterTarget = PluginJsonV1.encodeToString(chapterTarget)
        assertContains(encodedChapterTarget, "\"type\":\"mangaChapter\"")
        assertEquals(chapterTarget, PluginJsonV1.decodeFromString<NavigationTargetV1>(encodedChapterTarget))
        assertFailsWith<IllegalArgumentException> {
            NavigationTargetV1.MangaChapter(EntityKeyV1("m-1", "manga.example"), " ").requireValid()
        }
    }

    @Test
    fun validatesMangaPageImageRequestRetryAndFailedUrlRules() {
        val baseRequest =
            MangaPageImageRequestV1(
                key = EntityKeyV1("m-1", "manga.example"),
                chapterId = "ch-1",
                pageId = "p-1",
                pageToken = "ptok-1",
            )
        baseRequest.requireValid()

        // Serialization & round-trip of MangaPageImageReasonV1 + retry fields
        val retryRequest =
            baseRequest.copy(
                attempt = 0,
                reason = MangaPageImageReasonV1.Initial,
            )
        retryRequest.requireValid()
        val initialJson = PluginJsonV1.encodeToString(retryRequest)
        assertContains(initialJson, "\"attempt\":0")
        assertContains(initialJson, "\"reason\":\"initial\"")
        assertFalse("\"failedUrl\"" in initialJson)
        assertEquals(retryRequest, PluginJsonV1.decodeFromString<MangaPageImageRequestV1>(initialJson))

        val resolveRetryRequest =
            baseRequest.copy(
                attempt = 1,
                reason = MangaPageImageReasonV1.ResolveRetry,
            )
        resolveRetryRequest.requireValid()
        val resolveRetryJson = PluginJsonV1.encodeToString(resolveRetryRequest)
        assertContains(resolveRetryJson, "\"reason\":\"resolveRetry\"")

        val imageFailedRequest =
            baseRequest.copy(
                attempt = 100,
                reason = MangaPageImageReasonV1.ImageLoadFailed,
                failedUrl = "https://hath.example/1.jpg?token=abc",
            )
        imageFailedRequest.requireValid()
        val imageFailedJson = PluginJsonV1.encodeToString(imageFailedRequest)
        assertContains(imageFailedJson, "\"reason\":\"imageLoadFailed\"")
        assertContains(imageFailedJson, "\"failedUrl\":\"https://hath.example/1.jpg?token=abc\"")
        assertEquals(imageFailedRequest, PluginJsonV1.decodeFromString<MangaPageImageRequestV1>(imageFailedJson))

        // ImageLoadFailed is valid with failedUrl = null
        baseRequest.copy(attempt = 2, reason = MangaPageImageReasonV1.ImageLoadFailed, failedUrl = null).requireValid()

        // attempt bounds: only null or 0..100
        listOf(-1, 101).forEach { invalidAttempt ->
            assertFailsWith<IllegalArgumentException>("attempt=$invalidAttempt") {
                baseRequest.copy(attempt = invalidAttempt).requireValid()
            }
        }

        // failedUrl is rejected when reason is null, Initial, or ResolveRetry
        listOf(null, MangaPageImageReasonV1.Initial, MangaPageImageReasonV1.ResolveRetry).forEach { invalidReason ->
            assertFailsWith<IllegalArgumentException>("reason=$invalidReason") {
                baseRequest
                    .copy(
                        attempt = 1,
                        reason = invalidReason,
                        failedUrl = "https://hath.example/1.jpg",
                    ).requireValid()
            }
        }

        // failedUrl must be non-blank, non-credential absolute HTTPS URL without #fragment
        listOf(
            "",
            "   ",
            "http://hath.example/1.jpg",
            "/relative/1.jpg",
            "https://user:pass@hath.example/1.jpg",
            "https://user@hath.example/1.jpg",
            "https://hath.example/1.jpg#frag",
            "https://hath.example/1.jpg#",
            "https://hath.example/1.jpg\n",
            "https://hath.example/with space.jpg",
        ).forEach { badUrl ->
            assertFailsWith<IllegalArgumentException>("badUrl=$badUrl") {
                baseRequest
                    .copy(
                        attempt = 1,
                        reason = MangaPageImageReasonV1.ImageLoadFailed,
                        failedUrl = badUrl,
                    ).requireValid()
            }
        }
    }

    @Test
    fun apiV2AllowsPostMutateWithoutPostDetail() {
        val v2MutateOnly =
            manifestWithCapabilities(
                apiVersion = 2,
                capabilitiesJson =
                    """
                    "${PluginAbiV1.Capabilities.MANGA}": {
                      "operations": {
                        "detail": {},
                        "pages": {},
                        "comments": { "directions": ["refresh", "older"] }
                      }
                    },
                    "${PluginAbiV1.Capabilities.POST}": {
                      "operations": { "mutate": {} }
                    }
                    """.trimIndent(),
            )
        assertTrue(v2MutateOnly.validate().isValid, v2MutateOnly.validate().errors.toString())

        // apiVersion = 1 with post.mutate without detail -> invalid
        val v1MutateOnly =
            manifestWithCapabilities(
                apiVersion = 1,
                capabilitiesJson =
                    """
                    "${PluginAbiV1.Capabilities.TIMELINE}": {
                      "operations": { "page": { "directions": ["refresh"] } }
                    },
                    "${PluginAbiV1.Capabilities.POST}": {
                      "operations": { "mutate": {} }
                    }
                    """.trimIndent(),
                timelinesJson = """[{ "id": "home", "title": "Home", "defaultForNewAccount": true }]""",
            )
        assertFalse(v1MutateOnly.validate().isValid)

        // apiVersion = 1 rejects manga/v1
        val v1Manga =
            manifestWithCapabilities(
                apiVersion = 1,
                capabilitiesJson =
                    """
                    "${PluginAbiV1.Capabilities.MANGA}": {
                      "operations": { "detail": {}, "pages": {} }
                    }
                    """.trimIndent(),
            )
        assertFalse(v1Manga.validate().isValid)

        // apiVersion = 2 with post context/delete/contextTabs without detail -> invalid
        listOf(
            """{ "context": {}, "mutate": {} }""",
            """{ "delete": {}, "mutate": {} }""",
            """{ "contextTabs": {}, "contextPage": {}, "mutate": {} }""",
        ).forEach { invalidPostOps ->
            val invalidManifest =
                manifestWithCapabilities(
                    apiVersion = 2,
                    capabilitiesJson =
                        """
                        "${PluginAbiV1.Capabilities.MANGA}": {
                          "operations": { "detail": {}, "pages": {} }
                        },
                        "${PluginAbiV1.Capabilities.POST}": {
                          "operations": $invalidPostOps
                        }
                        """.trimIndent(),
                )
            assertFalse(invalidManifest.validate().isValid, invalidPostOps)
        }

        // Non-paging manga operations reject directions
        listOf("detail", "pages", "pageImage").forEach { nonPagingOp ->
            val withDirections =
                manifestWithCapabilities(
                    apiVersion = 2,
                    capabilitiesJson =
                        """
                        "${PluginAbiV1.Capabilities.MANGA}": {
                          "operations": {
                            "detail": ${if (nonPagingOp == "detail") """{ "directions": ["refresh"] }""" else "{}"},
                            "pages": ${if (nonPagingOp == "pages") """{ "directions": ["refresh"] }""" else "{}"},
                            "pageImage": ${if (nonPagingOp == "pageImage") """{ "directions": ["refresh"] }""" else "{}"}
                          }
                        }
                        """.trimIndent(),
                )
            assertFalse(withDirections.validate().isValid, nonPagingOp)
        }

        // Session exposure invariants: post.mutate alone does not expose POST detail or count as displayable
        assertFalse(PluginAbiV1.isDisplayableCapability(PluginAbiV1.Capabilities.POST, setOf("mutate")))
        assertTrue(PluginAbiV1.isDisplayableCapability(PluginAbiV1.Capabilities.MANGA, setOf("detail", "pages")))

        val authSession =
            PluginSessionCapabilitiesV1(
                declarations = v2MutateOnly.socialPlatform.capabilities,
                operations =
                    mapOf(
                        PluginAbiV1.Capabilities.MANGA to setOf("detail", "pages", "comments"),
                        PluginAbiV1.Capabilities.POST to setOf("mutate"),
                    ),
                authenticated = true,
            )
        assertFalse(authSession.canExpose(PluginAbiV1.Capabilities.POST))
        assertTrue(authSession.hasOperation(PluginAbiV1.Capabilities.POST, "mutate"))
        assertTrue(authSession.canExpose(PluginAbiV1.Capabilities.MANGA))

        val guestSession =
            PluginSessionCapabilitiesV1(
                declarations = v2MutateOnly.socialPlatform.capabilities,
                operations = mapOf(PluginAbiV1.Capabilities.MANGA to setOf("detail", "pages")),
                authenticated = false,
            )
        assertTrue(guestSession.canExpose(PluginAbiV1.Capabilities.MANGA))
    }

    private fun validImageMedia(): MediaV1 =
        MediaV1(
            id = "m-1",
            type = MediaTypeV1.Image,
            url = "https://cdn.manga.example/cover.jpg",
            width = 800,
            height = 1200,
        )

    private fun validManga(): MangaV1 =
        MangaV1(
            key = EntityKeyV1("m-1", "manga.example"),
            title = "Sample Manga",
            altTitles = listOf("サンプル漫画"),
            cover = validImageMedia(),
            authors = listOf("Author One"),
            artists = listOf("Artist One"),
            status = MangaStatusV1.Ongoing,
            content = RichTextV1(value = "Synopsis"),
            url = "https://manga.example/series/1",
            updatedAt = "2026-11-21T00:00:00Z",
            readingDirection = MangaReadingDirectionV1.Rtl,
            chapters = listOf(MangaChapterV1(id = "ch-1", title = "Chapter 1")),
        )

    private fun manifestWithCapabilities(
        apiVersion: Int,
        capabilitiesJson: String,
        timelinesJson: String = "[]",
    ): PluginManifestV1 =
        PluginJsonV1.decodeFromString(
            """
            {
              "schemaVersion": 1,
              "apiVersion": $apiVersion,
              "id": "dev.example.manga",
              "version": "0.1.0",
              "name": "Manga sample",
              "permissions": { "mediaNetwork": true },
              "platform": {
                "id": "MangaSample",
                "name": "Manga sample",
                "capabilities": {
                  $capabilitiesJson
                },
                "timelines": $timelinesJson
              }
            }
            """.trimIndent(),
        )
}
