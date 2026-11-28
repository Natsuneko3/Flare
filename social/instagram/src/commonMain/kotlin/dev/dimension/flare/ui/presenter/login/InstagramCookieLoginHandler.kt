package dev.dimension.flare.ui.presenter.login

import dev.dimension.flare.data.datasource.instagram.toInstagramProfile
import dev.dimension.flare.data.network.instagram.InstagramRequestException
import dev.dimension.flare.data.network.instagram.InstagramService
import dev.dimension.flare.data.platform.INSTAGRAM_HOST
import dev.dimension.flare.data.platform.INSTAGRAM_PLATFORM_ID
import dev.dimension.flare.data.platform.InstagramCredential
import dev.dimension.flare.data.platform.extractInstagramCredential
import dev.dimension.flare.data.platform.validInstagramCookieValue
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

private val cookieFields = listOf("sessionid", "csrftoken", "ds_user_id")
private const val LOGIN_ACTION = "login"

internal class InstagramCookieLoginHandler(
    private val context: LoginContext,
) : LoginMethodHandler {
    private val accountService: AccountService by koinInject()
    private val verification = Mutex()
    private val inputs = mutableMapOf<String, String>()
    private val complete: Boolean get() = cookieFields.all { !inputs[it].isNullOrBlank() }
    private val mutableState = MutableStateFlow(form())
    override val state = mutableState.asStateFlow()
    override val effects = emptyFlow<LoginEffect>()

    override fun updateField(
        id: String,
        value: String,
    ) {
        if (id !in cookieFields || state.value.loading) return
        inputs[id] = value
        mutableState.value = form()
    }

    override suspend fun perform(actionId: String) {
        if (actionId != LOGIN_ACTION || state.value.loading || !complete) return
        val values = inputs.mapValues { it.value.trim() }
        val invalidField = cookieFields.firstOrNull { !validInstagramCookieValue(values.getValue(it)) }
        if (invalidField != null) {
            mutableState.value = form(error = "Check the value of $invalidField.")
            return
        }
        val credential = extractInstagramCredential(cookieFields.joinToString("; ") { "$it=${values.getValue(it)}" })
        if (credential == null) {
            mutableState.value = form(error = "Check that sessionid and ds_user_id belong to the same Instagram account.")
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
        inputs.clear()
        mutableState.value = form()
    }

    override fun close() = clear()

    private fun form(
        loading: Boolean = false,
        error: String? = null,
    ): LoginFlowState =
        LoginFlowState(
            fields =
                cookieFields.map { name ->
                    LoginField(name, LoginFieldType.PasswordInput, UiText.Raw(name), value = inputs[name].orEmpty())
                },
            actions = listOf(LoginAction(LOGIN_ACTION, UiStrings.Login, enabled = !loading && complete)),
            loading = loading,
            error = error,
        )
}
