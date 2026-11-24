package dev.dimension.flare.feature.plugin.manifest

import dev.dimension.flare.feature.plugin.host.PluginInvocationContextV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginInvocationLocaleTest {
    @Test
    fun normalizesIcuKeywordsAndPosixVariants() {
        assertEquals("en-US", normalizePluginInvocationLocale("en-US@rg=cnzzzz"))
        assertEquals("zh-Hans-CN", normalizePluginInvocationLocale("zh_Hans_CN@hours=h24;fw=mon"))
        assertEquals("zh-CN", normalizePluginInvocationLocale("zh_CN@calendar=chinese"))
        assertEquals("en-CN", normalizePluginInvocationLocale("en_CN@mu=celsius;ms=metric"))
        assertEquals("zh-CN", normalizePluginInvocationLocale("zh_CN.UTF-8@calendar=chinese"))
        assertEquals("en-US", normalizePluginInvocationLocale("en_US_POSIX"))
    }

    @Test
    fun fallsBackToDefaultForInvalidHostLocales() {
        val invalid = listOf("C", "POSIX", "POSIX_US", "", "_US", "en__US", "a-US")
        invalid.forEach { locale ->
            assertEquals("en", normalizePluginInvocationLocale(locale), "Expected fallback for $locale")
        }
    }

    @Test
    fun normalizedLocaleSucceedsWhileRawInvalidLocaleStillFails() {
        val rawInvalid = "en-US@rg=cnzzzz"
        val normalized = normalizePluginInvocationLocale(rawInvalid)

        val detectorContext =
            PluginInvocationContextV1.detector(
                pluginId = PLUGIN_ID,
                platformId = PLATFORM_ID,
                packageHash = PACKAGE_HASH,
                candidateOrigin = ORIGIN,
                locale = normalized,
            )
        assertEquals("en-US", detectorContext.metadata.locale)

        val pluginContext =
            PluginInvocationContextV1.plugin(
                pluginId = PLUGIN_ID,
                packageHash = PACKAGE_HASH,
                httpOrigins = emptySet(),
                locale = normalized,
                platformId = PLATFORM_ID,
            )
        assertEquals("en-US", pluginContext.metadata.locale)

        assertFailsWith<IllegalArgumentException> {
            PluginInvocationContextV1.detector(
                pluginId = PLUGIN_ID,
                platformId = PLATFORM_ID,
                packageHash = PACKAGE_HASH,
                candidateOrigin = ORIGIN,
                locale = rawInvalid,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            PluginInvocationContextV1.plugin(
                pluginId = PLUGIN_ID,
                packageHash = PACKAGE_HASH,
                httpOrigins = emptySet(),
                locale = rawInvalid,
                platformId = PLATFORM_ID,
            )
        }
    }

    private companion object {
        const val PLUGIN_ID = "dev.example.plugin"
        const val PLATFORM_ID = "example"
        const val ORIGIN = "https://example.com"
        val PACKAGE_HASH = "a".repeat(64)
    }
}
