package dev.dimension.flare.ui.presenter.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import dev.dimension.flare.common.ShareUrlPolicy
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.accountKeyOrNull
import dev.dimension.flare.data.datasource.microblog.accountProfileFlow
import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.NoActiveAccountException
import dev.dimension.flare.data.repository.accountServiceFlow
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.flatMapLatestUiState
import dev.dimension.flare.ui.model.flattenUiState
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.presenter.PresenterBase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

public class ProfileSharePresenter(
    private val accountType: AccountType,
    private val userKey: MicroBlogKey?,
) : PresenterBase<ProfileSharePresenter.State>() {
    private val accountRepository: AccountRepository by koinInject()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val profileFlow by lazy {
        when (accountType) {
            is AccountType.Specific -> {
                accountRepository
                    .getFlow(accountType.accountKey)
                    .distinctUntilChangedBy { state ->
                        when (state) {
                            is UiState.Success -> state.data.accountKey
                            is UiState.Error -> state.throwable
                            is UiState.Loading -> Unit
                        }
                    }.flatMapLatest { accountState ->
                        when (accountState) {
                            is UiState.Loading -> flowOf(UiState.Loading())
                            is UiState.Error -> flowOf(UiState.Error(accountState.throwable))
                            is UiState.Success ->
                                accountServiceFlow(accountType, accountRepository).flatMapLatestUiState { service ->
                                    resolveProfileShareFlow(service = service, userKey = userKey)
                                }
                        }
                    }
            }

            AccountType.Guest,
            is AccountType.GuestHost,
            is AccountType.GuestPlugin,
            -> {
                accountServiceFlow(accountType, accountRepository).flatMapLatestUiState { service ->
                    resolveProfileShareFlow(service = service, userKey = userKey)
                }
            }
        }
    }

    @Composable
    override fun body(): State {
        val profile by profileFlow.flattenUiState()
        val shareUrl = normalizeProfileShareUrl(profile)

        return ProfileShareState(
            profile = profile,
            shareUrl = shareUrl,
        )
    }

    @Immutable
    public interface State {
        public val profile: UiState<UiProfile>
        public val shareUrl: String?
    }

    private data class ProfileShareState(
        override val profile: UiState<UiProfile>,
        override val shareUrl: String?,
    ) : State
}

internal fun resolveProfileShareFlow(
    service: MicroblogDataSource,
    userKey: MicroBlogKey?,
): Flow<UiState<UiProfile>> =
    if (userKey == null || userKey == service.accountKeyOrNull) {
        service.accountProfileFlow() ?: flowOf(UiState.Error(NoActiveAccountException))
    } else {
        service.capabilities.profile
            ?.userHandler
            ?.userById(userKey.id)
            ?.toUi()
            ?: flowOf(UiState.Error(NoActiveAccountException))
    }

internal fun normalizeProfileShareUrl(profile: UiState<UiProfile>): String? =
    ShareUrlPolicy.normalize(profile.takeSuccess()?.url)
