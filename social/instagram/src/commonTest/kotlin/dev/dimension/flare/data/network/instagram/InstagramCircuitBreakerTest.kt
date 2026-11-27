package dev.dimension.flare.data.network.instagram

import dev.dimension.flare.data.platform.extractInstagramCredential
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InstagramCircuitBreakerTest {
    @Test
    fun challengeStopsBackgroundCallsUntilVerifiedLogin() =
        runTest {
            for ((code, reason) in listOf(401 to "", 403 to "", 200 to "challenge_required", 400 to "checkpoint_required")) {
                val gate = InstagramCircuitBreaker()
                var calls = 0
                assertFailsWith<InstagramAuthenticationRequired> {
                    gate.run {
                        calls++
                        gate.classify(code, listOf(JsonObject(mapOf("message" to JsonPrimitive(reason)))), null, null, false)
                    }
                }
                assertFailsWith<InstagramAuthenticationRequired> { gate.run { calls++ } }
                assertEquals(1, calls)
                gate.run(explicitLogin = true) { calls++ }
                gate.run { calls++ }
                assertEquals(3, calls)
            }
        }

    @Test
    fun rateLimitStopsEvenExplicitLoginUntilServerCooldownEnds() =
        runTest {
            var now = 1000L
            val gate = InstagramCircuitBreaker { now }
            var calls = 0
            assertFailsWith<InstagramRateLimited> {
                gate.run {
                    calls++
                    gate.classify(429, emptyList(), null, "1800", false)
                }
            }
            now += 900000L
            assertFailsWith<InstagramRateLimited> { gate.run(explicitLogin = true) { calls++ } }
            assertEquals(1, calls)
            now += 900000L
            gate.run { calls++ }
            assertEquals(2, calls)
        }

    @Test
    fun loginRedirectAndRestrictedSessionStopPolling() =
        runTest {
            val gate = InstagramCircuitBreaker()
            assertFailsWith<InstagramAuthenticationRequired> {
                gate.run {
                    gate.classify(
                        302,
                        emptyList(),
                        "/accounts/login/?next=/",
                        null,
                        false,
                    )
                }
            }
            assertFailsWith<InstagramRestricted> {
                gate.run(true) {
                    gate.classify(400, listOf(JsonObject(mapOf("message" to JsonPrimitive("feedback_required")))), null, null, false)
                }
            }
            assertFailsWith<InstagramRestricted> { gate.run { error("Blocked request must not execute") } }
        }

    @Test
    fun validatesImportedSessionsAndRefreshesOnlyRootCookies() {
        val cookie = "sessionid=123%3Aabc; ds_user_id=123; csrftoken=start"
        val credential = assertNotNull(extractInstagramCredential(cookie))
        assertNull(extractInstagramCredential(cookie.replace("ds_user_id=123", "ds_user_id=456")))
        assertNull(extractInstagramCredential(cookie + "; csrftoken=other"))
        assertNull(extractInstagramCredential(cookie + "\r\nInjected: x"))
        assertEquals(credential, credential.rotated(listOf("csrftoken=wrong; Domain=other.example; Path=/", "rur=wrong; Path=/private")))
        assertEquals(credential.copy(csrfToken = "next"), credential.rotated(listOf("csrftoken=next; Domain=.instagram.com; Path=/")))
    }
}
