package dev.dimension.flare.ui.presenter.home

import androidx.datastore.core.DataStore
import dev.dimension.flare.data.datastore.model.AccountSelectionPreferences
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val GUEST_PLUGIN_KEY_PREFIX: String = "__guest_plugin__:"

internal fun guestPluginSearchKey(
    platformId: String,
    host: String,
): MicroBlogKey = MicroBlogKey("$GUEST_PLUGIN_KEY_PREFIX$platformId", host)

internal fun MicroBlogKey.toSearchAccountType(): AccountType =
    if (id.startsWith(GUEST_PLUGIN_KEY_PREFIX)) {
        val platformId = id.removePrefix(GUEST_PLUGIN_KEY_PREFIX)
        if (platformId.isNotEmpty() && host.isNotEmpty()) {
            AccountType.GuestPlugin(platformId = platformId, host = host)
        } else {
            AccountType.Specific(this)
        }
    } else {
        AccountType.Specific(this)
    }

internal fun AccountType?.toSearchSelectionKeyOrNull(): MicroBlogKey? =
    when (this) {
        is AccountType.Specific -> {
            accountKey
        }

        is AccountType.GuestPlugin -> {
            guestPluginSearchKey(platformId, host)
        }

        AccountType.Guest,
        is AccountType.GuestHost,
        null,
        -> {
            null
        }
    }

internal class RememberedAccountSelection(
    private val preferences: DataStore<AccountSelectionPreferences>,
    private val appScope: CoroutineScope,
    private val accountMutationMutex: Mutex,
    private val accountExists: suspend (MicroBlogKey) -> Boolean,
) {
    // null means restoration is pending; Guest means the account list is ready and empty.
    private val selected = MutableStateFlow<AccountType?>(null)
    val selectedAccountType = selected.asStateFlow()
    val selectedAccountKey = selected.map { it.toSearchSelectionKeyOrNull() }.distinctUntilChanged()
    private var accounts: List<MicroBlogKey>? = null
    private var restored = false
    private var initialAccountKey: MicroBlogKey? = null

    suspend fun restore() {
        if (restored) return
        val saved =
            try {
                // Wait behind saves from a closing page without changing the preference.
                accountMutationMutex.withLock {
                    preferences.updateData { it }.selectedAccountKey
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.printStackTrace()
                null
            }
        if (!restored) {
            initialAccountKey = saved
            restored = true
        }
        resolveAccount()
    }

    fun updateAccounts(keys: List<MicroBlogKey>) {
        accounts = keys
        resolveAccount()
    }

    fun selectAccount(key: MicroBlogKey) {
        if (accounts?.contains(key) != true) return
        restored = true
        initialAccountKey = null
        selected.value = key.toSearchAccountType()
        persist { if (accountExists(key)) it.copy(selectedAccountKey = key) else it }
    }

    private fun resolveAccount() {
        val keys = accounts ?: return
        if (!restored) return
        val previous = selected.value.toSearchSelectionKeyOrNull() ?: initialAccountKey
        initialAccountKey = null
        selected.value = (previous?.takeIf { it in keys } ?: keys.firstOrNull())?.toSearchAccountType() ?: AccountType.Guest
        if (previous != null && previous !in keys) {
            persist {
                if (it.selectedAccountKey == previous && !accountExists(previous)) it.copy(selectedAccountKey = null) else it
            }
        }
    }

    private fun persist(transform: suspend (AccountSelectionPreferences) -> AccountSelectionPreferences) {
        // Enqueue in click order, with a lifetime independent of the page.
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                accountMutationMutex.withLock {
                    preferences.updateData { transform(it) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.printStackTrace()
            }
        }
    }
}
