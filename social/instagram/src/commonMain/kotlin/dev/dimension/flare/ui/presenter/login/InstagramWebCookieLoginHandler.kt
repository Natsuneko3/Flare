package dev.dimension.flare.ui.presenter.login

import dev.dimension.flare.data.datasource.instagram.toInstagramProfile
import dev.dimension.flare.data.network.instagram.InstagramRequestException
import dev.dimension.flare.data.network.instagram.InstagramService
import dev.dimension.flare.data.platform.INSTAGRAM_HOST
import dev.dimension.flare.data.platform.INSTAGRAM_PLATFORM_ID
import dev.dimension.flare.data.platform.INSTAGRAM_WEB_HOST
import dev.dimension.flare.data.platform.InstagramCredential
import dev.dimension.flare.data.platform.extractInstagramCredential
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

private const val LOGIN_URL = "https://$INSTAGRAM_WEB_HOST/accounts/login/"
private const val COOKIE_PROBE_URL = "https://$INSTAGRAM_WEB_HOST/"
private const val COOKIE_FIELD = "cookie"
private const val LOGIN_ACTION = "login"
private val cookieNames = listOf("sessionid", "csrftoken", "ds_user_id", "mid", "ig_did", "rur")

internal class InstagramWebCookieLoginHandler(
    private val context: LoginContext,
) : LoginMethodHandler {
    private val accountService: AccountService by koinInject()
    private val manual = context.methodType == LoginMethodType.CredentialImport
    private val verification = Mutex()
    private var input = ""
    private var browserPending = false
    private val mutableState = MutableStateFlow(form())
    private val mutableEffects = MutableSharedFlow<LoginEffect>()
    override val state = mutableState.asStateFlow()
    override val effects = mutableEffects.asSharedFlow()

    override fun updateField(
        id: String,
        value: String,
    ) {
        if (!manual || id != COOKIE_FIELD || state.value.loading) return
        input = value
        mutableState.value = form()
    }

    override suspend fun perform(actionId: String) {
        if (actionId != LOGIN_ACTION || state.value.loading) return
        if (manual) {
            resume(input)
        } else {
            browserPending = true
            mutableState.value = form()
            mutableEffects.emit(
                LoginEffect.OpenWebCookieLogin(LOGIN_URL, listOf(LoginCookieProbe(COOKIE_PROBE_URL, cookieNames))),
            )
        }
    }

    // WebCookie flows use origin-scoped probes; URI callbacks must never be treated as credentials.
    override fun canResume(value: String): Boolean = false

    override suspend fun resume(value: String) {
        if (!manual) return
        val credential = extractInstagramCredential(value)
        if (credential == null) {
            mutableState.value = form(error = "The cookie must include sessionid, csrftoken and ds_user_id.")
            return
        }
        verify(credential)
    }

    override suspend fun checkCookies(snapshot: LoginCookieSnapshot): Boolean {
        if (manual || !browserPending) return false
        val credential = snapshot.instagramCredential() ?: return false
        browserPending = false
        // Both a saved account and a terminal verification error finish the browser step.
        // Returning to the login screen exposes the error and avoids silent verification retries.
        verify(credential)
        return true
    }

    private suspend fun verify(credential: InstagramCredential) {
        if (!verification.tryLock()) return
        try {
            mutableState.value = form(loading = true)
            val current = MutableStateFlow(credential)
            val service =
                InstagramService(
                    credentialFlow = current,
                    onCredentialRefreshed = { previous, refreshed -> current.compareAndSet(previous, refreshed) },
                    proxy = context.proxy,
                )
            val accountKey = MicroBlogKey(credential.dsUserId, INSTAGRAM_HOST)
            context.requireReloginAccount(accountKey)
            val profile = service.verifySession().toInstagramProfile(accountKey)
            check(profile.key == accountKey)
            accountService.addAccount(
                account = UiAccount(accountKey = accountKey, platformId = INSTAGRAM_PLATFORM_ID),
                credential = current.value.copy(username = profile.handle.normalizedRaw),
                serializer = InstagramCredential.serializer(),
            )
        } catch (error: CancellationException) {
            mutableState.value = form()
            throw error
        } catch (error: Exception) {
            val message =
                when (error) {
                    is InstagramRequestException -> {
                        error.message
                    }

                    is ReloginAccountMismatchException -> {
                        "Sign in to the same Instagram account to reconnect it."
                    }

                    else -> {
                        if (error.message == "This account belongs to another platform") {
                            "This account is already connected through another platform in Flare."
                        } else {
                            "Flare could not verify or save this Instagram session."
                        }
                    }
                }
            mutableState.value = form(error = message)
            return
        } finally {
            verification.unlock()
        }
        clear()
        context.onSuccess()
    }

    override fun clear() {
        browserPending = false
        input = ""
        mutableState.value = form()
    }

    private fun form(
        loading: Boolean = false,
        error: String? = null,
    ): LoginFlowState =
        LoginFlowState(
            fields =
                if (manual) {
                    listOf(LoginField(COOKIE_FIELD, LoginFieldType.PasswordInput, UiText.Raw("Instagram Cookie"), value = input))
                } else {
                    emptyList()
                },
            actions = listOf(LoginAction(LOGIN_ACTION, UiStrings.Login, enabled = !loading && (!manual || input.isNotBlank()))),
            loading = loading,
            error = error,
        )
}

internal fun LoginCookieSnapshot.instagramCredential(): InstagramCredential? {
    val cookies = values.filter { it.sourceUrl == COOKIE_PROBE_URL && it.name in cookieNames }
    if (cookies.any { it.value.any { ch -> ch == ';' || ch.isISOControl() } }) return null
    return extractInstagramCredential(cookies.joinToString("; ") { "${it.name}=${it.value}" })
}
