package dev.dimension.flare.data.datasource.microblog.handler

import dev.dimension.flare.common.Cacheable
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.model.DbProfileTabs
import dev.dimension.flare.data.datasource.microblog.CachedProfileTab
import dev.dimension.flare.data.datasource.microblog.ProfileCapability
import dev.dimension.flare.data.datasource.microblog.ProfileTab
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.DbAccountType
import dev.dimension.flare.model.MicroBlogKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.Json
import kotlin.native.HiddenFromObjC

@HiddenFromObjC
public class ProfileTabsHandler(
    private val accountType: AccountType,
    private val profile: ProfileCapability,
) {
    private val database: CacheDatabase by koinInject()
    private val accounts: AccountRepository by koinInject()

    public fun profileTabs(userKey: MicroBlogKey): Cacheable<ImmutableList<ProfileTab>> {
        val dbAccountType = accountType as DbAccountType
        return Cacheable(
            fetchSource = {
                accounts.refreshAccountCache(
                    accountType,
                    fetch = { profileTabsJson.encodeToString(profile.profileTabs(userKey).map { it.toCached() }) },
                    save = { tabsJson ->
                        val dao = database.userDao()
                        if (dao.getProfileTabsSync(dbAccountType, userKey)?.tabsJson != tabsJson) {
                            dao.insertProfileTabs(DbProfileTabs(dbAccountType, userKey, tabsJson))
                        }
                    },
                )
            },
            cacheSource = {
                database
                    .userDao()
                    .getProfileTabs(
                        accountType = dbAccountType,
                        userKey = userKey,
                    ).distinctUntilChanged()
                    .mapNotNull { entry ->
                        entry?.tabsJson?.let { raw ->
                            runCatching {
                                profileTabsJson.decodeFromString<List<CachedProfileTab>>(raw)
                            }.getOrNull()?.let { cached ->
                                profile.restoreProfileTabs(userKey, cached)
                            }
                        }
                    }
            },
        )
    }

    private companion object {
        val profileTabsJson: Json =
            Json {
                ignoreUnknownKeys = true
            }
    }
}
