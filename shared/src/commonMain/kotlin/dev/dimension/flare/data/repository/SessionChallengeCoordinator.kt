package dev.dimension.flare.data.repository

import androidx.compose.runtime.Immutable
import dev.dimension.flare.data.model.ProxyRef
import dev.dimension.flare.model.MicroBlogKey

public interface SessionChallengeCoordinator {
    public suspend fun beginSession(challengeId: String): SessionChallengeHandle?

    public fun invalidateTicket(challengeId: String)

    public fun invalidateAccountChallenges(accountKey: MicroBlogKey)

    public fun invalidateSiblingTickets(
        accountKey: MicroBlogKey,
        excludeChallengeId: String,
    )
}

public interface SessionChallengeHandle : AutoCloseable {
    public val challengeId: String
    public val accountKey: MicroBlogKey
    public val platformId: String
    public val proxyRef: ProxyRef
    public val challengeUrl: String
    public val seedCookies: List<SessionChallengeSeedCookie>
    public val probes: List<SessionChallengeCookieProbe>

    @Throws(Exception::class)
    public suspend fun check(snapshot: SessionChallengeCookieSnapshot): SessionChallengeCheckResult

    public fun cancel()

    override fun close()
}

@Immutable
public data class SessionChallengeSeedCookie(
    val name: String,
    val value: String,
)

@Immutable
public data class SessionChallengeCookieRequirement(
    val name: String,
    val required: Boolean,
)

@Immutable
public data class SessionChallengeCookieProbe(
    val url: String,
    val cookies: List<SessionChallengeCookieRequirement>,
)

@Immutable
public data class SessionChallengeCookieName(
    val sourceUrl: String,
    val name: String,
)

@Immutable
public data class SessionChallengeCookieAttributes(
    val domain: String? = null,
    val hostOnly: Boolean? = null,
    val path: String? = null,
    val expiresAt: Long? = null,
    val session: Boolean? = null,
    val secure: Boolean? = null,
    val httpOnly: Boolean? = null,
    val sameSite: String? = null,
)

@Immutable
public data class SessionChallengeCookieValue(
    val sourceUrl: String,
    val name: String,
    val value: String,
    val attributes: SessionChallengeCookieAttributes? = null,
)

@Immutable
public data class SessionChallengeCookieSnapshot(
    val cookies: List<SessionChallengeCookieValue>,
    val userAgent: String? = null,
)

public sealed interface SessionChallengeCheckResult {
    public data object Busy : SessionChallengeCheckResult

    public data class AwaitingCookies(
        val missing: List<SessionChallengeCookieName>,
    ) : SessionChallengeCheckResult

    public data object Pending : SessionChallengeCheckResult

    public data object Completed : SessionChallengeCheckResult
}
