package dev.dimension.flare.data.repository

import dev.dimension.flare.data.datasource.microblog.capabilities
import dev.dimension.flare.data.model.MixedTimelineTabItem
import dev.dimension.flare.data.model.TabSettings
import dev.dimension.flare.data.model.TimelineTabItem
import dev.dimension.flare.data.model.tab.GroupSource
import dev.dimension.flare.data.model.tab.SYSTEM_HOME_MIXED_TIMELINE_ID
import dev.dimension.flare.data.model.tab.TimelineMergePolicy
import dev.dimension.flare.data.model.tab.TimelinePresentation
import dev.dimension.flare.data.model.tab.TimelineResolver
import dev.dimension.flare.data.model.tab.TimelineSlot
import dev.dimension.flare.data.model.tab.TimelineSlotContent
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiAccount
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import kotlin.native.HiddenFromObjC

@Single(createdAtStart = true)
@HiddenFromObjC
public class AccountTabSyncCoordinator internal constructor(
    private val accountRepository: AccountRepository,
    private val settingsRepository: SettingsRepository,
    private val coroutineScope: CoroutineScope,
    private val timelineResolver: TimelineResolver,
    private val mangaProgressRepository: MangaProgressRepository,
) {
    init {
        coroutineScope.launch {
            removeTabsForDeletedAccounts()
            accountRepository.accountChanges.collect { change ->
                try {
                    when (change) {
                        is AccountChange.Added -> addDefaultTabs(change.account)
                        is AccountChange.Removed -> removeTabsForAccount(change.accountKey)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    error.printStackTrace()
                }
            }
        }
        val initialGuestPlatformIds =
            accountRepository.platformRegistry.platforms.value
                .mapNotNull { spec -> spec.platformId.takeIf { spec.standaloneGuestHost != null } }
                .toSet()
        coroutineScope.launch {
            var previousGuestPlatformIds = initialGuestPlatformIds
            accountRepository.platformRegistry.platforms
                .map { platforms ->
                    platforms
                        .mapNotNull { spec -> spec.platformId.takeIf { spec.standaloneGuestHost != null } }
                        .toSet()
                }.distinctUntilChanged()
                .collect { activeGuestPlatformIds ->
                    try {
                        val removedGuestPlatformIds = previousGuestPlatformIds - activeGuestPlatformIds
                        previousGuestPlatformIds = activeGuestPlatformIds
                        removedGuestPlatformIds.forEach { platformId ->
                            accountRepository.clearRemovedGuestPlatform(platformId)
                            mangaProgressRepository.deleteGuestByPlatformId(platformId)
                        }
                        removeTabsForInactiveGuestPlatforms()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        error.printStackTrace()
                    }
                }
        }
    }

    public suspend fun addGuestPluginDefaultTabs(platformId: String) {
        val defaultTabs =
            accountRepository.platformRegistry.platforms.value
                .firstOrNull { it.platformId == platformId && it.standaloneGuestHost != null }
                ?.guestDefaultTabs
                .orEmpty()
        if (defaultTabs.isEmpty()) {
            return
        }
        settingsRepository.updateTabSettingsV2 {
            val activeSpec =
                accountRepository.platformRegistry.platforms.value
                    .firstOrNull { it.platformId == platformId && it.standaloneGuestHost != null }
                    ?: return@updateTabSettingsV2 this
            val defaultSlots = activeSpec.guestDefaultTabs.map(timelineResolver::toSlot)
            if (defaultSlots.isEmpty()) {
                return@updateTabSettingsV2 this
            }
            val shouldEnableSystemHomeMixedTimeline =
                homeSlots.anySystemHomeMixedTimeline() ||
                    homeSlots.countNonSystemHomeTabs() < 2
            val newSlots =
                (homeSlots + defaultSlots)
                    .normalizeSystemHomeMixedTimeline(
                        enabled = shouldEnableSystemHomeMixedTimeline,
                    )
            val newSettings = copy(homeSlots = newSlots)
            if (newSettings == this) {
                this
            } else {
                newSettings
            }
        }
    }

    private suspend fun removeTabsForDeletedAccounts() {
        val existingAccounts =
            accountRepository.allAccounts
                .first()
                .mapTo(linkedSetOf()) { it.accountKey }
        settingsRepository.updateTabSettingsV2 {
            copy(
                homeSlots =
                    homeSlots
                        .mapNotNull { it.cleanupForExistingAccounts(existingAccounts) }
                        .normalizeSystemHomeMixedTimeline(
                            enabled = homeSlots.anySystemHomeMixedTimeline(),
                        ),
            )
        }
    }

    private suspend fun addDefaultTabs(account: UiAccount) {
        val defaultTabs =
            accountRepository.withAccountChanges {
                val current = accountRepository.find(account.accountKey)
                if (current == null || current.platformId != account.platformId || !current.platformAvailable) {
                    return@withAccountChanges emptyList()
                }
                accountRepository
                    .getOrCreateDataSource(current)
                    .capabilities
                    .tabCatalog
                    ?.configuration
                    ?.defaultTabs
                    .orEmpty()
            }
        if (defaultTabs.isEmpty()) {
            return
        }
        settingsRepository.updateTabSettingsV2 {
            val defaultSlots = defaultTabs.map(timelineResolver::toSlot)
            val shouldEnableSystemHomeMixedTimeline =
                homeSlots.anySystemHomeMixedTimeline() ||
                    homeSlots.countNonSystemHomeTabs() < 2
            val newSlots =
                (homeSlots + defaultSlots)
                    .normalizeSystemHomeMixedTimeline(
                        enabled = shouldEnableSystemHomeMixedTimeline,
                    )
            val newSettings = copy(homeSlots = newSlots)
            if (newSettings == this) {
                this
            } else {
                newSettings
            }
        }
    }

    private suspend fun removeTabsForAccount(accountKey: MicroBlogKey) {
        settingsRepository.updateTabSettingsV2 {
            copy(
                homeSlots =
                    homeSlots
                        .mapNotNull { it.cleanupForRemovedAccount(accountKey) }
                        .normalizeSystemHomeMixedTimeline(
                            enabled = homeSlots.anySystemHomeMixedTimeline(),
                        ),
            )
        }
    }

    private fun TimelineSlot.cleanupForExistingAccounts(accountKeys: Set<MicroBlogKey>): TimelineSlot? =
        cleanupAccountSlots { accountKey ->
            accountKey == null || accountKey in accountKeys
        }

    private fun TimelineSlot.cleanupForRemovedAccount(accountKey: MicroBlogKey): TimelineSlot? =
        cleanupAccountSlots { slotAccountKey ->
            slotAccountKey == null || slotAccountKey != accountKey
        }

    private fun TimelineSlot.cleanupAccountSlots(shouldKeep: (MicroBlogKey?) -> Boolean): TimelineSlot? =
        when (val slotContent = content) {
            is TimelineSlotContent.Source -> {
                val accountKey = timelineResolver.resolveAccountKey(this)
                val normalized =
                    if (accountKey != null && slotContent.source.ownerAccountKey == null) {
                        copy(
                            content =
                                TimelineSlotContent.Source(
                                    slotContent.source.copy(ownerAccountKey = accountKey),
                                ),
                        )
                    } else {
                        this
                    }
                if (shouldKeep(accountKey)) {
                    normalized
                } else {
                    null
                }
            }

            is TimelineSlotContent.Group -> {
                val sanitizedChildren =
                    slotContent.children
                        .mapNotNull { it.cleanupAccountSlots(shouldKeep) }
                        .distinctBy { it.id }
                if (sanitizedChildren.isEmpty()) {
                    null
                } else if (sanitizedChildren == slotContent.children) {
                    this
                } else {
                    copy(content = slotContent.copy(children = sanitizedChildren))
                }
            }
        }

    private suspend fun removeTabsForInactiveGuestPlatforms() {
        settingsRepository.updateTabSettingsV2 {
            val activeGuestPlatformIds =
                accountRepository.platformRegistry.platforms.value
                    .mapNotNull { spec -> spec.platformId.takeIf { spec.standaloneGuestHost != null } }
                    .toSet()
            val newSlots =
                homeSlots
                    .mapNotNull { it.cleanupForActiveGuestPlatforms(activeGuestPlatformIds) }
                    .normalizeSystemHomeMixedTimeline(
                        enabled = homeSlots.anySystemHomeMixedTimeline(),
                    )
            if (newSlots == homeSlots) {
                this
            } else {
                copy(homeSlots = newSlots)
            }
        }
    }

    private fun TimelineSlot.cleanupForActiveGuestPlatforms(activeGuestPlatformIds: Set<String>): TimelineSlot? =
        when (val slotContent = content) {
            is TimelineSlotContent.Source -> {
                val platformId = timelineResolver.resolveGuestPlatformId(this)
                val normalized =
                    if (platformId != null && slotContent.source.ownerPlatformId == null) {
                        copy(
                            content =
                                TimelineSlotContent.Source(
                                    slotContent.source.copy(ownerPlatformId = platformId),
                                ),
                        )
                    } else {
                        this
                    }
                if (platformId == null || platformId in activeGuestPlatformIds) {
                    normalized
                } else {
                    null
                }
            }

            is TimelineSlotContent.Group -> {
                val sanitizedChildren =
                    slotContent.children
                        .mapNotNull { it.cleanupForActiveGuestPlatforms(activeGuestPlatformIds) }
                        .distinctBy { it.id }
                if (sanitizedChildren.isEmpty()) {
                    null
                } else if (sanitizedChildren == slotContent.children) {
                    this
                } else {
                    copy(content = slotContent.copy(children = sanitizedChildren))
                }
            }
        }
}

internal fun List<TimelineSlot>.normalizeSystemHomeMixedTimeline(enabled: Boolean): List<TimelineSlot> {
    val deduplicatedSlots = distinctBy { it.id }
    val existingSystemHomeGroup = deduplicatedSlots.firstOrNull { it.isSystemHomeMixedTimeline() }
    val slotsWithoutSystemHomeGroup = deduplicatedSlots.filterNot { it.isSystemHomeMixedTimeline() }
    if (!enabled || slotsWithoutSystemHomeGroup.size < 2) {
        return slotsWithoutSystemHomeGroup
    }

    val systemHomeGroup =
        TimelineSlot(
            id = SYSTEM_HOME_MIXED_TIMELINE_ID,
            content =
                TimelineSlotContent.Group(
                    children = slotsWithoutSystemHomeGroup,
                    source = GroupSource.SystemHome,
                    mergePolicy =
                        (existingSystemHomeGroup?.content as? TimelineSlotContent.Group)?.mergePolicy
                            ?: TimelineMergePolicy.TimePerPage,
                ),
            presentation = existingSystemHomeGroup?.presentation ?: TimelinePresentation(),
        )
    val targetIndex = deduplicatedSlots.indexOfFirst { it.isSystemHomeMixedTimeline() }.takeIf { it >= 0 } ?: 0
    return slotsWithoutSystemHomeGroup
        .toMutableList()
        .apply {
            add(minOf(targetIndex, size), systemHomeGroup)
        }
}

private fun List<TimelineSlot>.anySystemHomeMixedTimeline(): Boolean = any { it.isSystemHomeMixedTimeline() }

private fun List<TimelineSlot>.countNonSystemHomeTabs(): Int = count { !it.isSystemHomeMixedTimeline() }

private fun TimelineSlot.isSystemHomeMixedTimeline(): Boolean = (content as? TimelineSlotContent.Group)?.source == GroupSource.SystemHome

internal fun TabSettings.sanitizeDuplicateTabKeys(): TabSettings {
    val sanitizedTabs =
        mainTabs
            .mapNotNull { it.sanitizeDuplicateTabKeys() }
            .distinctBy { it.key }
    return if (sanitizedTabs == mainTabs) {
        this
    } else {
        copy(mainTabs = sanitizedTabs)
    }
}

private fun TimelineTabItem.sanitizeDuplicateTabKeys(): TimelineTabItem? =
    when (this) {
        is MixedTimelineTabItem -> {
            val sanitizedSubTabs =
                subTimelineTabItem
                    .mapNotNull { it.sanitizeDuplicateTabKeys() }
                    .distinctBy { it.key }
                    .toImmutableList()
            if (sanitizedSubTabs.isEmpty()) {
                null
            } else if (sanitizedSubTabs == subTimelineTabItem) {
                this
            } else {
                copy(subTimelineTabItem = sanitizedSubTabs)
            }
        }

        else -> {
            this
        }
    }
