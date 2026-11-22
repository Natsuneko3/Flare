package dev.dimension.flare.feature.plugin.manifest

import dev.dimension.flare.feature.plugin.installer.PluginRegistrationInspector
import dev.dimension.flare.feature.plugin.installer.requireBackgroundScript
import dev.dimension.flare.feature.plugin.runtime.createTestPluginJavaScriptEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginTimelineProcessorManifestTest {
    @Test
    fun portableRegexFindsMatchesWithinPostText() {
        val matches = requireNotNull(compileTimelineFilterRegex("(?:score|quote) [0-9]{1,3}\\b"))
        assertTrue(matches("Final score 42 today"))
        assertFalse(matches("No score in this post"))
    }

    @Test
    fun shorthandClassesAndWordBoundariesHaveTheSameAsciiMeaningOnEveryHost() {
        fun matches(
            pattern: String,
            text: String,
        ) = requireNotNull(compileTimelineFilterRegex(pattern)) { pattern }(text)

        assertTrue(matches("""^\d+$""", "42"))
        assertFalse(matches("""^\d+$""", "٤٢"))
        assertTrue(matches("""^\p{Nd}+$""", "٤٢"))
        assertTrue(matches("""^[\D_]+$""", "٤_"))
        assertFalse(matches("""^[^\W]+$""", "東京"))
        assertTrue(matches("""^[^\W]+$""", "A_2"))
        assertTrue(matches("""^\s$""", "\u000c"))
        assertFalse(matches("""^\s$""", "\u000b"))
        assertTrue(matches("""^[\S]$""", "\u00a0"))
        assertTrue(matches("""\bABC\b""", "東ABC京"))
        assertFalse(matches("""\b東京\b""", "東京"))
        assertTrue(matches("""\B東京\B""", "東京"))
        assertFalse(matches("""(?i)\bK\b""", "K"))
        assertFalse(matches("""^[[:digit:]]+$""", "٤٢"))
        assertTrue(matches("""^[[:^digit:]]+$""", "٤٢"))
        assertTrue(matches("""^[]\w]$""", "]"))
        assertTrue(matches("""^[a&&b]$""", "&"))
        assertTrue(matches("""^\Q\d[\b]\E\\w$""", """\d[\b]\w"""))
    }

    @Test
    fun standaloneTimelineProcessorRequiresApiV2ValidFilterAndProcessBatchMethod() {
        val base =
            PluginManifestV1(
                apiVersion = 2,
                id = "org.example.gpg",
                version = "0.1.0",
                name = PluginTextV1.Literal("GPG Decryptor"),
                timelineProcessor =
                    PluginTimelineProcessorManifestV1(
                        maxBatchSize = 20,
                        filter = PluginTimelineFilterManifestV1(containsAny = listOf("-----BEGIN PGP MESSAGE-----")),
                    ),
            )
        assertTrue(base.validate(PluginMethodTableV1(2, setOf("timelineProcessor.processBatch"))).isValid)
        assertFalse(base.copy(apiVersion = 1).validate(PluginMethodTableV1(1, setOf("timelineProcessor.processBatch"))).isValid)
        assertFalse(base.validate(PluginMethodTableV1(2, emptySet())).isValid)
        assertFalse(
            base
                .copy(
                    timelineProcessor = PluginTimelineProcessorManifestV1(filter = PluginTimelineFilterManifestV1(regex = "[unclosed")),
                ).validate(PluginMethodTableV1(2, setOf("timelineProcessor.processBatch")))
                .isValid,
        )
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(linkHosts = listOf("https://polymarket.com")),
                        ),
                ).validate(PluginMethodTableV1(2, setOf("timelineProcessor.processBatch")))
                .isValid,
        )
    }

    @Test
    fun validatesBatchSizeApiVersionFilterLimitsAndStorageNamespaces() {
        val methodTable = PluginMethodTableV1(2, setOf("timelineProcessor.processBatch"))
        val base =
            PluginManifestV1(
                apiVersion = 2,
                id = "org.example.market",
                version = "0.1.0",
                name = PluginTextV1.Literal("Market Widgets"),
                timelineProcessor = PluginTimelineProcessorManifestV1(),
            )

        assertTrue(base.validate(methodTable).isValid)
        assertTrue(base.copy(timelineProcessor = PluginTimelineProcessorManifestV1(maxBatchSize = 1)).validate(methodTable).isValid)
        assertTrue(base.copy(timelineProcessor = PluginTimelineProcessorManifestV1(maxBatchSize = 50)).validate(methodTable).isValid)
        assertFalse(base.copy(timelineProcessor = PluginTimelineProcessorManifestV1(apiVersion = 2)).validate(methodTable).isValid)
        assertFalse(base.copy(timelineProcessor = PluginTimelineProcessorManifestV1(maxBatchSize = 0)).validate(methodTable).isValid)
        assertFalse(base.copy(timelineProcessor = PluginTimelineProcessorManifestV1(maxBatchSize = 51)).validate(methodTable).isValid)

        // Filter: empty containsAny allowed when regex/linkHosts/requireLinks used
        assertTrue(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter =
                                PluginTimelineFilterManifestV1(
                                    containsAny = emptyList(),
                                    regex = "\\$[A-Z]{1,6}\\b",
                                    linkHosts = listOf("polymarket.com", "sub-1.example.org"),
                                    requireLinks = true,
                                ),
                        ),
                ).validate(methodTable)
                .isValid,
        )

        // containsAny limits: non-blank, <= 256 UTF-8 bytes, <= 32 items
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(containsAny = listOf("   ")),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(containsAny = listOf("é".repeat(129))),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertTrue(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(containsAny = List(32) { "needle-$it" }),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(containsAny = List(33) { "needle-$it" }),
                        ),
                ).validate(methodTable)
                .isValid,
        )

        // regex limits: non-blank, <= 512 chars
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(regex = "  "),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(regex = "a".repeat(513)),
                        ),
                ).validate(methodTable)
                .isValid,
        )

        // linkHosts limits: canonical lowercase hostnames without scheme, port, leading/trailing/double dot, <= 32 items
        for (invalidHost in listOf(
            "Polymarket.com",
            "polymarket.com:443",
            ".polymarket.com",
            "polymarket.com.",
            "bad..host.com",
            "-bad.com",
            "bad-.com",
            "polymarket.com/event",
            "",
        )) {
            assertFalse(
                base
                    .copy(
                        timelineProcessor =
                            PluginTimelineProcessorManifestV1(
                                filter = PluginTimelineFilterManifestV1(linkHosts = listOf(invalidHost)),
                            ),
                    ).validate(methodTable)
                    .isValid,
                "Expected invalid linkHost: $invalidHost",
            )
        }
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            filter = PluginTimelineFilterManifestV1(linkHosts = List(33) { "host$it.example.com" }),
                        ),
                ).validate(methodTable)
                .isValid,
        )

        // cache.storageNamespaces: null or 0..16 distinct valid storage namespace names
        assertTrue(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            cache = PluginTranslationCacheManifestV1(storageNamespaces = emptyList()),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertTrue(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            cache = PluginTranslationCacheManifestV1(storageNamespaces = List(16) { "ns-$it" }),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            cache = PluginTranslationCacheManifestV1(storageNamespaces = List(17) { "ns-$it" }),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            cache = PluginTranslationCacheManifestV1(storageNamespaces = listOf("dup", "dup")),
                        ),
                ).validate(methodTable)
                .isValid,
        )
        assertFalse(
            base
                .copy(
                    timelineProcessor =
                        PluginTimelineProcessorManifestV1(
                            cache = PluginTranslationCacheManifestV1(storageNamespaces = listOf("bad\u0000ns")),
                        ),
                ).validate(methodTable)
                .isValid,
        )
    }

    @Test
    fun requiresBackgroundScriptAndInspectsTimelineProcessorRegistration() =
        runBlocking<Unit> {
            val manifest =
                PluginManifestV1(
                    apiVersion = 2,
                    id = "org.example.gpg",
                    version = "0.1.0",
                    name = PluginTextV1.Literal("GPG Decryptor"),
                    timelineProcessor = PluginTimelineProcessorManifestV1(),
                )
            assertFails { manifest.requireBackgroundScript(hasScript = false) }
            manifest.requireBackgroundScript(hasScript = true)

            val inspector = PluginRegistrationInspector(engineFactory = { host -> createTestPluginJavaScriptEngine(host) })
            val processor = "defineFlareTimelineProcessorPlugin({ async processBatch() { return { results: [] }; } });"
            val inspected = inspector.inspect(processor, apiVersion = 2)
            assertEquals(setOf("timelineProcessor.processBatch"), inspected.methods)
            assertTrue(manifest.validate(inspected).isValid)

            val combined =
                inspector.inspect(
                    "defineFlareSocialPlugin({});" +
                        "defineFlareTranslatePlugin({ translateDocumentJson() {}, translateBatchDocumentJson() {} });" +
                        processor,
                    apiVersion = 2,
                )
            assertTrue("timelineProcessor.processBatch" in combined.methods)

            for (invalid in listOf(
                processor + processor,
                "defineFlareTimelineProcessorPlugin(null);",
                "defineFlareTimelineProcessorPlugin([]);",
                "defineFlareSocialPlugin({ timelineProcessor: { processBatch() {} } });",
                "defineFlareSocialPlugin({}); __flareValidateRegistrationV1(); $processor",
            )) {
                assertFails(invalid) { inspector.inspect(invalid, apiVersion = 2) }
            }
        }
}
