package dev.dimension.flare.ui.presenter.login

import dev.dimension.flare.data.platform.InstagramPlatformSpec
import dev.dimension.flare.data.platform.instagramMediaId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstagramLoginTest {
    private val origin = "https://www.instagram.com/"
    private val header = "sessionid=123%3Aabc; csrftoken=csrf; ds_user_id=123"
    private val cookies =
        listOf(
            LoginCookieValue(origin, "sessionid", "123%3Aabc"),
            LoginCookieValue(origin, "csrftoken", "csrf"),
            LoginCookieValue(origin, "ds_user_id", "123"),
        )

    @Test
    fun probesOnlyAcceptTheInstagramOrigin() {
        val scoped = LoginCookieSnapshot(values = cookies, rawHeader = "ds_user_id=456")
        assertEquals("123", scoped.instagramCredential()?.dsUserId)
        assertNull(LoginCookieSnapshot(rawHeader = header).instagramCredential())
        assertNull(LoginCookieSnapshot(values = cookies.map { it.copy(sourceUrl = "https://example.com/") }).instagramCredential())
        assertNull(LoginCookieSnapshot(values = listOf(cookies[0].copy(value = header))).instagramCredential())
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
                listOf(LoginMethodType.WebCookie, LoginMethodType.CredentialImport),
                InstagramPlatformSpec.methods.map { it.type },
            )
        }
}
