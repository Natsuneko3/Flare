package dev.dimension.flare.ui.presenter.login

import dev.dimension.flare.data.datasource.instagram.toInstagramProfile
import dev.dimension.flare.data.network.instagram.InstagramRequestException
import dev.dimension.flare.data.network.instagram.InstagramService
import dev.dimension.flare.data.platform.INSTAGRAM_HOST
import dev.dimension.flare.data.platform.INSTAGRAM_PLATFORM_ID
import dev.dimension.flare.data.platform.InstagramCredential
import dev.dimension.flare.data.platform.extractInstagramCredential
import dev.dimension.flare.data.repository.AccountService
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiStrings
import dev.dimension.flare.ui.model.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex

private const val COOKIE_FIELD = "cookie"
private const val LOGIN_ACTION = "login"

internal class InstagramCookieLoginHandler(
    private val context: LoginContext,
) : LoginMethodHandler {
    private val accountService: AccountService by koinInject()
    private val verification = Mutex()
    private var input = ""
    private val mutableState = MutableStateFlow(form())
    override val state = mutableState.asStateFlow()
    override val effects = emptyFlow<LoginEffect>()

    override fun updateField(
        id: String,
        value: String,
    ) {
        if (id != COOKIE_FIELD || state.value.loading) return
        input = value
        mutableState.value = form()
    }

    override suspend fun perform(actionId: String) {
        if (actionId != LOGIN_ACTION || state.value.loading || input.isBlank()) return
        val credential = extractInstagramCredential(input)
        if (credential == null) {
            mutableState.value = form(error = "The cookie must include sessionid, csrftoken and ds_user_id.")
            return
        }
        verify(credential)
    }

    override fun canResume(value: String): Boolean = false

    override suspend fun resume(value: String) = Unit

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
        input = ""
        mutableState.value = form()
    }

    override fun close() = clear()

    private fun form(
        loading: Boolean = false,
        error: String? = null,
    ): LoginFlowState =
        LoginFlowState(
            fields = listOf(LoginField(COOKIE_FIELD, LoginFieldType.PasswordInput, UiText.Raw("Instagram Cookie"), value = input)),
            actions = listOf(LoginAction(LOGIN_ACTION, UiStrings.Login, enabled = !loading && input.isNotBlank())),
            loading = loading,
            error = error,
        )
}
