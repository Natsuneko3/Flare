package dev.dimension.flare.data.repository

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
import dev.dimension.flare.model.PlatformSpec
import dev.dimension.flare.testPlatformRegistry
import dev.dimension.flare.ui.model.UiAccount
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.takeSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertNotNull
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
        val registry = testPlatformRegistry()
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

        fun attachPlatform() {
            registry.replaceExternal(
                PLATFORM,
                object : PlatformSpec by registry.defaultGuest {
                    override val platformId: String = PLATFORM
                    override val isDefaultGuest: Boolean = false

                    override fun createDataSource(context: PlatformDataSourceContext): MicroblogDataSource {
                        lastContext = context
                        return object : MicroblogDataSource by UnavailableMicroblogDataSource {}
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
