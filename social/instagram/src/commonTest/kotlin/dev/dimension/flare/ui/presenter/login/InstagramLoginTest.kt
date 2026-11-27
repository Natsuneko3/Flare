package dev.dimension.flare.ui.presenter.login

import dev.dimension.flare.data.platform.InstagramPlatformSpec
import dev.dimension.flare.data.platform.extractInstagramCredential
import dev.dimension.flare.data.platform.instagramMediaId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstagramLoginTest {
    private val header = "sessionid=123%3Aabc; csrftoken=csrf; ds_user_id=123"

    @Test
    fun acceptsPastedCookieValuesAndHeaderNames() {
        val expected = extractInstagramCredential(header)
        assertEquals("123", expected?.dsUserId)
        assertEquals(expected, extractInstagramCredential("Cookie: $header"))
        assertEquals(expected, extractInstagramCredential("cookie: $header\n"))
    }

    @Test
    fun rejectsConflictingAccountsAndAdditionalHeaderLines() {
        assertNull(extractInstagramCredential(header.replace("ds_user_id=123", "ds_user_id=456")))
        assertNull(extractInstagramCredential("$header; ds_user_id=456"))
        assertNull(extractInstagramCredential("cookie: $header\r\nAccept: */*"))
    }

    @Test
    fun cookieImportUsesAMaskedFieldAndRequiresInput() {
        val handler = InstagramPlatformSpec.createHandler(LoginContext("instagram.com", LoginMethodType.CredentialImport, {}))
        val field = handler.state.value.fields.single()
        assertEquals(LoginFieldType.PasswordInput, field.type)
        assertFalse(handler.state.value.actions.single().enabled)
        assertFalse(handler.canResume(header))
        handler.updateField(field.id, header)
        assertTrue(handler.state.value.actions.single().enabled)
        handler.clear()
        assertEquals("", handler.state.value.fields.single().value)
    }

    @Test
    fun deepLinkShortcodesResolveToTheOriginalMediaIdWithoutOverflow() {
        assertEquals("64", instagramMediaId("BA"))
        assertEquals("18446744073709551615", instagramMediaId("P__________"))
        assertNull(instagramMediaId("QAAAAAAAAAA"))
        assertNull(instagramMediaId(".."))
        assertNull(instagramMediaId(""))
    }

    @Test
    fun detectorPrefersBuiltInLoginForBothHostAliases() =
        runTest {
            assertEquals("instagram.com", InstagramPlatformDetector.detect("www.instagram.com")?.host)
            assertEquals("instagram.com", InstagramPlatformDetector.detect("INSTAGRAM.COM")?.host)
            assertNull(InstagramPlatformDetector.detect("instagram.com.example"))
            assertEquals(
                listOf(LoginMethodType.CredentialImport),
                InstagramPlatformSpec.methods.map { it.type },
            )
        }
}
