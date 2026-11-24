package dev.dimension.flare.ui.screen.serviceselect

import dev.dimension.flare.data.model.ProxyMode
import dev.dimension.flare.data.model.ProxyProtocol
import dev.dimension.flare.data.repository.SessionChallengeCookieProbe
import dev.dimension.flare.data.repository.SessionChallengeCookieRequirement
import dev.dimension.flare.data.repository.SessionChallengeCookieValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionChallengePolicyTest {
    @Test
    fun proxyComparisonAllowsIdenticalConnectionsAndFailsClosedOnMismatch() {
        assertEquals(
            AndroidChallengeProxyDecision.Allowed,
            evaluateAndroidChallengeProxy(
                accountConnection = ChallengeProxyEndpoint(ProxyMode.SYSTEM),
                globalConnection = ChallengeProxyEndpoint(ProxyMode.SYSTEM),
            ),
        )
        assertEquals(
            AndroidChallengeProxyDecision.Allowed,
            evaluateAndroidChallengeProxy(
                accountConnection = ChallengeProxyEndpoint(ProxyMode.PROXY, ProxyProtocol.SOCKS5, "127.0.0.1", 1080),
                globalConnection = ChallengeProxyEndpoint(ProxyMode.PROXY, ProxyProtocol.SOCKS5, "127.0.0.1", 1080),
            ),
        )
        assertEquals(
            AndroidChallengeProxyDecision.AccountProxyMismatch,
            evaluateAndroidChallengeProxy(
                accountConnection = ChallengeProxyEndpoint(ProxyMode.DIRECT),
                globalConnection = ChallengeProxyEndpoint(ProxyMode.SYSTEM),
            ),
        )
        assertEquals(
            AndroidChallengeProxyDecision.AccountProxyMismatch,
            evaluateAndroidChallengeProxy(
                accountConnection = ChallengeProxyEndpoint(ProxyMode.PROXY, ProxyProtocol.HTTPS, "127.0.0.1", 8443),
                globalConnection = ChallengeProxyEndpoint(ProxyMode.PROXY, ProxyProtocol.SOCKS5, "127.0.0.1", 1080),
            ),
        )
    }

    @Test
    fun hostOnlySeedCookieFormatOmitsDomainAndEnforcesPathAndSecure() {
        val formatted = formatAndroidHostOnlySeedCookie(name = "ipb_member_id", value = "12345=abc")
        assertEquals("ipb_member_id=12345=abc; Path=/; Secure", formatted)
        assertFalse(formatted.contains("Domain=", ignoreCase = true))
    }

    @Test
    fun probeCookieParserExtractsOnlyDeclaredProbeCookies() {
        val probe =
            SessionChallengeCookieProbe(
                url = "https://exhentai.org/gallery",
                cookies =
                    listOf(
                        SessionChallengeCookieRequirement("cf_clearance", required = true),
                        SessionChallengeCookieRequirement("igneous", required = false),
                    ),
            )
        val parsed =
            parseAndroidProbeCookies(
                rawCookieHeader = "undeclared=secret; cf_clearance=cf=val==; igneous=ign123; session_other=nope",
                probe = probe,
            )
        assertEquals(
            listOf(
                SessionChallengeCookieValue(
                    sourceUrl = "https://exhentai.org/gallery",
                    name = "cf_clearance",
                    value = "cf=val==",
                ),
                SessionChallengeCookieValue(
                    sourceUrl = "https://exhentai.org/gallery",
                    name = "igneous",
                    value = "ign123",
                ),
            ),
            parsed,
        )
    }

    @Test
    fun challengeHttpsOriginNormalizesHttpsUrlsAndRejectsNonHttpsOrUserinfo() {
        assertEquals("https://exhentai.org", "https://ExHentai.ORG/g/123?p=1".challengeHttpsOriginOrNull())
        assertEquals("https://exhentai.org", "https://exhentai.org:443/challenge".challengeHttpsOriginOrNull())
        assertEquals("https://localhost:8443", "https://localhost:8443/challenge".challengeHttpsOriginOrNull())
        assertNull("http://exhentai.org/challenge".challengeHttpsOriginOrNull())
        assertNull("javascript:alert(1)".challengeHttpsOriginOrNull())
        assertNull("https://user:pass@exhentai.org/challenge".challengeHttpsOriginOrNull())
    }
}
