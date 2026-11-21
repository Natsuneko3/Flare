package dev.dimension.flare.data.repository

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.room3.Room
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.createTestFileSystem
import dev.dimension.flare.createTestRootPath
import dev.dimension.flare.data.database.app.AppDatabase
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.createDatabaseDriver
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.UnavailableMicroblogDataSource
import dev.dimension.flare.data.datastore.AppDataStore
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.deleteTestRootPath
import dev.dimension.flare.memoryDatabaseBuilder
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.PlatformDataSourceContext
import dev.dimension.flare.model.PlatformRegistry
import dev.dimension.flare.model.PlatformRuntimeData
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.testPlatformRuntimeData
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.takeSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountServiceFastPathTest {
    private val accountKey = MicroBlogKey("alice", "fastpath.example")
    private val accountType = AccountType.Specific(accountKey)

    @Test
    fun accountServiceProviderEmitsCachedDataSourceOnFirstFrameAndDropsStaleEntries() =
        withFixture {
            attachPlatform()

            val coldFirst =
                moleculeFlow(RecompositionMode.Immediate) {
                    accountServiceProvider(accountType, accountRepository)
                }.first()
            assertTrue(coldFirst is UiState.Loading)

            val warmAccount = accountRepository.find(accountKey)!!
            val expected = accountRepository.getOrCreateDataSource(warmAccount)

            val warmFirst =
                moleculeFlow(RecompositionMode.Immediate) {
                    accountServiceProvider(accountType, accountRepository)
                }.first()
            assertSame(expected, warmFirst.takeSuccess())
            assertSame(expected, accountServiceFlow(accountType, accountRepository).first())

            // Session-level invalidateDataSource clears peekDataSource immediately
            lastContext?.invalidateDataSource()
            assertNull(accountRepository.peekDataSource(accountType))

            // Re-warm, then detach platform -> peekDataSource returns null immediately
            accountRepository.getOrCreateDataSource(accountRepository.find(accountKey)!!)
            assertNotNull(accountRepository.peekDataSource(accountType))
            detachPlatform()
            assertNull(accountRepository.peekDataSource(accountType))

            // Reattach + re-warm, then delete account -> peekDataSource returns null immediately
            attachPlatform()
            accountRepository.getOrCreateDataSource(accountRepository.find(accountKey)!!)
            assertNotNull(accountRepository.peekDataSource(accountType))
            accountRepository.delete(accountKey)
            assertNull(accountRepository.peekDataSource(accountType))
        }

    @Test
    fun accountServiceProviderResetsOnAccountSwitchAndReusesSingleGuestInstance() =
        withFixture {
            attachPlatform()
            val aliceSource = accountRepository.getOrCreateDataSource(accountRepository.find(accountKey)!!)
            val missingBobType = AccountType.Specific(MicroBlogKey("bob", accountKey.host))
            val selectedAccountType = MutableStateFlow<AccountType>(accountType)
            val observed = MutableStateFlow<UiState<MicroblogDataSource>?>(null)
            val job =
                scope.launch {
                    moleculeFlow(RecompositionMode.Immediate) {
                        val currentType by selectedAccountType.collectAsState()
                        accountServiceProvider(currentType, accountRepository)
                    }.collect { observed.value = it }
                }

            val initial = observed.filterNotNull().first { it is UiState.Success }
            assertSame(aliceSource, initial.takeSuccess())

            selectedAccountType.value = missingBobType
            val afterSwitch = observed.filterNotNull().first { it.takeSuccess() !== aliceSource }
            assertNotSame(aliceSource, afterSwitch.takeSuccess())
            assertTrue(afterSwitch is UiState.Loading)
            job.cancelAndJoin()

            guestCreations.set(0)
            val guestStates = MutableStateFlow<UiState<MicroblogDataSource>?>(null)
            val guestJob =
                scope.launch {
                    moleculeFlow(RecompositionMode.Immediate) {
                        accountServiceProvider(AccountType.GuestHost("guest.example"), accountRepository)
                    }.collect { guestStates.value = it }
                }
            guestStates.filterNotNull().first { it is UiState.Success }
            assertEquals(1, guestCreations.get())
            guestJob.cancelAndJoin()
        }

    @Test
    fun deletingAnAccountDiscardsItsInFlightSourceAndObserversRecoverOnLogin() = checkInFlightCreation(readdBeforeRelease = false)

    @Test
    fun creationRetriesWithTheNewSessionAfterDeletingAndReaddingAnAccount() = checkInFlightCreation(readdBeforeRelease = true)

    private fun checkInFlightCreation(readdBeforeRelease: Boolean) =
        withFixture {
            val creating = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val closed = AtomicInteger()
            val removedSource =
                object : MicroblogDataSource by UnavailableMicroblogDataSource, AutoCloseable {
                    override fun close() {
                        closed.incrementAndGet()
                    }
                }
            val replacement = object : MicroblogDataSource by UnavailableMicroblogDataSource {}
            attachPlatform { context ->
                if (context.credential(String.serializer()) == "credential") {
                    creating.complete(Unit)
                    check(release.await(7, TimeUnit.SECONDS))
                    removedSource
                } else {
                    replacement
                }
            }

            val account = requireNotNull(accountRepository.find(accountKey))
            val source = scope.async(Dispatchers.IO) { accountRepository.getOrCreateDataSource(account) }
            try {
                creating.await()
                accountRepository.delete(accountKey)
                assertNull(accountRepository.find(accountKey))
                if (readdBeforeRelease) accountRepository.addAccount(account, "replacement")
            } finally {
                release.countDown()
            }
            if (readdBeforeRelease) {
                assertSame(replacement, source.await())
            } else {
                assertFailsWith<NoSuchElementException> { source.await() }
                assertNull(accountRepository.peekDataSource(accountType))
                assertTrue(accountRepository.allAccountServices.first().isEmpty())
                val subscriber =
                    scope.async(start = CoroutineStart.UNDISPATCHED) {
                        accountServiceFlow(accountType, accountRepository).first()
                    }
                assertFalse(subscriber.isCompleted)
                accountRepository.addAccount(account, "replacement")
                assertSame(replacement, subscriber.await())
            }
            assertEquals(1, closed.get())
            val service = accountRepository.allAccountServices.first().single()
            assertSame(replacement, service.dataSource)
            assertEquals(app.accountDao().getSessionId(accountKey), service.sessionId)
        }

    @Test
    fun loginHidesTheRetiredSourceWhileItCloses() =
        checkSessionRetirement { account -> accountRepository.addAccount(account, "replacement") }

    @Test
    fun restoreHidesTheRetiredSourceWhileItCloses() =
        checkSessionRetirement {
            accountRepository.restoreAccounts(listOf(accountKey)) { app.accountDao().setCredential(accountKey, "\"replacement\"") }
        }

    @Test
    fun deletionRejectsNewSourcesDuringCleanupAndRebindsTheMountedProviderOnLogin() =
        checkSessionRetirement(deleting = true) { accountRepository.delete(accountKey) }

    private fun checkSessionRetirement(
        deleting: Boolean = false,
        retire: suspend Fixture.(UiAccount) -> Unit,
    ) = withFixture {
        val closing = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val original =
            object : MicroblogDataSource by UnavailableMicroblogDataSource, AutoCloseable {
                override fun close() {
                    closing.complete(Unit)
                    check(release.await(7, TimeUnit.SECONDS))
                }
            }
        val replacement = object : MicroblogDataSource by UnavailableMicroblogDataSource {}
        val other = UiAccount(MicroBlogKey("bob", accountKey.host), PLATFORM)
        val otherSource = object : MicroblogDataSource by UnavailableMicroblogDataSource {}
        attachPlatform { context ->
            when {
                context.accountKey == other.accountKey -> otherSource
                context.credential(String.serializer()) == "credential" -> original
                else -> replacement
            }
        }
        accountRepository.addAccount(other, "bob")
        val account = requireNotNull(accountRepository.find(accountKey))
        assertSame(original, accountRepository.getOrCreateDataSource(account))
        val mounted = MutableStateFlow<UiState<MicroblogDataSource>?>(null)
        val observer =
            scope.launch {
                moleculeFlow(RecompositionMode.Immediate) {
                    accountServiceProvider(accountType, accountRepository)
                }.collect { mounted.value = it }
            }
        assertSame(original, mounted.filterNotNull().first { it is UiState.Success }.takeSuccess())

        val changing = scope.async(Dispatchers.IO) { retire(account) }
        val subscriber =
            try {
                closing.await()
                assertNull(accountRepository.peekDataSource(accountType))
                assertTrue(mounted.filterNotNull().first { it.takeSuccess() !== original } is UiState.Loading)
                val first =
                    scope.async(start = CoroutineStart.UNDISPATCHED) {
                        accountServiceFlow(accountType, accountRepository).first()
                    }
                assertFalse(first.isCompleted)
                assertSame(otherSource, accountRepository.getOrCreateDataSource(other))
                if (deleting) {
                    assertNotNull(accountRepository.find(accountKey))
                    assertFailsWith<NoSuchElementException> { accountRepository.getOrCreateDataSource(account) }
                    assertSame(
                        otherSource,
                        accountRepository.allAccountServices
                            .first()
                            .single()
                            .dataSource,
                    )
                }
                first
            } finally {
                release.countDown()
                changing.await()
            }
        if (deleting) accountRepository.addAccount(account, "replacement")
        assertSame(replacement, subscriber.await())
        assertSame(replacement, mounted.filterNotNull().first { it is UiState.Success }.takeSuccess())
        assertSame(replacement, accountRepository.peekDataSource(accountType))
        observer.cancelAndJoin()
    }

    private fun withFixture(block: suspend Fixture.() -> Unit) =
        runBlocking {
            val main = Executors.newSingleThreadExecutor { Thread(it, "account-fast-path") }.asCoroutineDispatcher()
            Dispatchers.setMain(main)
            val fixture = Fixture(CoroutineScope(main + SupervisorJob()))
            try {
                withContext(main) {
                    withTimeout(10_000) {
                        fixture.accountRepository.addAccount(UiAccount(accountKey, PLATFORM), "credential")
                        fixture.block()
                    }
                }
            } finally {
                fixture.close()
                Dispatchers.resetMain()
                main.close()
            }
        }

    private inner class Fixture(
        val scope: CoroutineScope,
    ) {
        val root = createTestRootPath()
        val storage = OkioFileStorage(createTestFileSystem(), root)
        val app = Room.memoryDatabaseBuilder<AppDatabase>().setDriver(createDatabaseDriver()).build()
        val cache = Room.memoryDatabaseBuilder<CacheDatabase>().setDriver(createDatabaseDriver()).build()
        val appData = AppDataStore(storage)
        val guestCreations = AtomicInteger(0)
        val registry =
            PlatformRegistry(
                PlatformRuntimeData(
                    platformSpecs =
                        testPlatformRuntimeData().platformSpecs.map { spec ->
                            if (spec.isDefaultGuest) {
                                object : PlatformSpec by spec {
                                    override fun guestDataSource(
                                        host: String,
                                        locale: String,
                                    ): MicroblogDataSource {
                                        guestCreations.incrementAndGet()
                                        return object : MicroblogDataSource by UnavailableMicroblogDataSource {}
                                    }
                                }
                            } else {
                                spec
                            }
                        },
                    extraTimelineSpecs = emptyList(),
                ),
            )
        val drafts = DraftRepository(app, DraftMediaStore(storage))
        var lastContext: PlatformDataSourceContext? = null
        val accountRepository =
            AccountRepository(
                app,
                scope,
                appData,
                cache,
                ProxyRepository(storage),
                registry,
                drafts,
            )

        fun attachPlatform(
            create: (
                PlatformDataSourceContext,
            ) -> MicroblogDataSource = { object : MicroblogDataSource by UnavailableMicroblogDataSource {} },
        ) {
            registry.replaceExternal(
                PLATFORM,
                object : PlatformSpec by registry.defaultGuest {
                    override val platformId: String = PLATFORM
                    override val isDefaultGuest: Boolean = false

                    override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource {
                        lastContext = context
                        return create(context)
                    }
                },
            )
        }

        fun detachPlatform() {
            registry.replaceExternal(PLATFORM, null)
        }

        suspend fun close() {
            scope.coroutineContext.job.cancelAndJoin()
            app.close()
            cache.close()
            deleteTestRootPath(root)
        }
    }

    private companion object {
        const val PLATFORM = "AccountFastPath"
    }
}
