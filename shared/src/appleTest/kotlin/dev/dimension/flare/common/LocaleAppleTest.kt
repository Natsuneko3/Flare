package dev.dimension.flare.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocaleAppleTest {
    @Test
    fun preservesValidStandardTags() {
        assertEquals("en-US", normalizeAppleLocaleTag("en_US"))
        assertEquals("zh-Hans-CN", normalizeAppleLocaleTag("zh_Hans_CN"))
        assertEquals("zh-Hans-JP", normalizeAppleLocaleTag("zh-Hans_JP"))
        assertEquals("es-419", normalizeAppleLocaleTag("es_419"))
        assertEquals("yue-Hant-HK", normalizeAppleLocaleTag("yue_Hant_HK"))
    }

    @Test
    fun stripsMacOsIcuKeywordModifiers() {
        assertEquals("en-US", normalizeAppleLocaleTag("en_US@rg=cnzzzz"))
        assertEquals("zh-Hans-CN", normalizeAppleLocaleTag("zh_Hans_CN@rg=uszzzz"))
        assertEquals("zh-CN", normalizeAppleLocaleTag("zh_CN@calendar=chinese"))
        assertEquals("zh-Hans-CN", normalizeAppleLocaleTag("zh_Hans_CN@hours=h24;fw=mon"))
        assertEquals("en-CN", normalizeAppleLocaleTag("en_CN@mu=celsius;ms=metric"))
    }

    @Test
    fun normalizesCodesetAndPosixVariants() {
        assertEquals("zh-CN", normalizeAppleLocaleTag("zh_CN.UTF-8@calendar=chinese"))
        assertEquals("en-US", normalizeAppleLocaleTag("en_US_POSIX"))
        assertEquals("en-US", normalizeAppleLocaleTag("en_US_POSIX@rg=cnzzzz"))
    }

    @Test
    fun rejectsInvalidLocaleIdentifiers() {
        val invalid =
            listOf(
                "C",
                "POSIX",
                "POSIX_US",
                "",
                "@calendar=gregorian",
                "_US",
                "en__US",
                "en_US_",
                "a_US",
                "123_US",
                "en_123456789",
            )
        invalid.forEach { identifier ->
            assertNull(normalizeAppleLocaleTag(identifier), "Expected null for $identifier")
        }
    }

    @Test
    fun resolvesLocaleWithPreferredLanguagesFallback() {
        assertEquals(
            "en-US",
            resolveAppleLanguageTag("en_US@rg=cnzzzz", listOf("zh-Hans-CN")),
        )
        assertEquals(
            "zh-Hans-CN",
            resolveAppleLanguageTag(
                "C",
                listOf("@rg=cnzzzz", "zh-Hans-CN@calendar=chinese", "en-US"),
            ),
        )
        assertEquals(
            "en",
            resolveAppleLanguageTag("POSIX", listOf("C", "POSIX_US", "")),
        )
    }

    @Test
    fun liveLocaleLanguageMatchesBcp47Pattern() {
        assertTrue(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*").matches(Locale.language))
    }
}
