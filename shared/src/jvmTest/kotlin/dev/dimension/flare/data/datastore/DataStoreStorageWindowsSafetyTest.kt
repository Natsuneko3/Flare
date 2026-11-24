package dev.dimension.flare.data.datastore

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioSerializer
import dev.dimension.flare.data.io.OkioFileStorage
import dev.dimension.flare.data.io.RetryingAtomicMoveFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okio.BufferedSink
import okio.BufferedSource
import okio.FileHandle
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.ForwardingSource
import okio.IOException
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DataStoreStorageWindowsSafetyTest {
    private val tempRoots = mutableListOf<java.nio.file.Path>()

    @AfterTest
    fun tearDown() {
        tempRoots.forEach { it.toFile().deleteRecursively() }
        tempRoots.clear()
    }

    private fun createTempRoot(): Path {
        val dir = Files.createTempDirectory("flare-datastore-windows-test")
        tempRoots.add(dir)
        return dir.toOkioPath()
    }

    @Test
    fun serializedStoragePreventsAtomicMoveWhileReadHandleIsOpen() =
        runBlocking {
            val root = createTempRoot()
            val winFs = WindowsReadLockedFileSystem(FileSystem.SYSTEM)
            val fileStorage = OkioFileStorage(fileSystem = winFs, root = root)
            val writeToTwoStarted = CompletableDeferred<Unit>()
            val readerDispatched = CompletableDeferred<Unit>()
            val serializer =
                object : OkioSerializer<Int> {
                    override val defaultValue: Int = 0

                    override suspend fun readFrom(source: BufferedSource): Int {
                        val raw = source.readUtf8().trim()
                        return if (raw.isEmpty()) 0 else raw.toInt()
                    }

                    override suspend fun writeTo(
                        t: Int,
                        sink: BufferedSink,
                    ) {
                        sink.writeUtf8(t.toString())
                        if (t == 2) {
                            writeToTwoStarted.complete(Unit)
                            readerDispatched.await()
                            // Hold the write scope open long enough for the concurrent reader to reach readScope.
                            delay(50)
                        }
                    }
                }
            val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val store =
                    DataStoreFactory.create(
                        storage = createDataStoreStorage("app_settings.pb", serializer, fileStorage),
                        scope = storeScope,
                    )

                assertEquals(1, store.updateData { 1 })

                val writeJob = async(Dispatchers.IO) { store.updateData { 2 } }
                withTimeout(5_000) { writeToTwoStarted.await() }
                val readJob =
                    async(Dispatchers.IO) {
                        readerDispatched.complete(Unit)
                        store.data.first()
                    }

                assertEquals(2, withTimeout(5_000) { writeJob.await() })
                assertEquals(2, withTimeout(5_000) { readJob.await() })
                assertEquals(0, winFs.overlapCount.get())
            } finally {
                storeScope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test
    fun concurrentUpdatesAndFirstReadsDoNotTriggerWindowsAccessDenied() =
        runBlocking {
            val root = createTempRoot()
            val winFs = WindowsReadLockedFileSystem(FileSystem.SYSTEM)
            val fileStorage = OkioFileStorage(fileSystem = winFs, root = root)
            val serializer =
                object : OkioSerializer<Int> {
                    override val defaultValue: Int = 0

                    override suspend fun readFrom(source: BufferedSource): Int {
                        val raw = source.readUtf8().trim()
                        return if (raw.isEmpty()) 0 else raw.toInt()
                    }

                    override suspend fun writeTo(
                        t: Int,
                        sink: BufferedSink,
                    ) {
                        sink.writeUtf8(t.toString())
                        yield()
                    }
                }
            val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val store =
                    DataStoreFactory.create(
                        storage = createDataStoreStorage("app_settings.pb", serializer, fileStorage),
                        scope = storeScope,
                    )
                val targetUpdates = 40
                val writer =
                    launch(Dispatchers.IO) {
                        repeat(targetUpdates) {
                            store.updateData { current -> current + 1 }
                        }
                    }
                val readers =
                    List(4) {
                        launch(Dispatchers.IO) {
                            while (store.data.first() < targetUpdates) {
                                yield()
                            }
                        }
                    }

                withTimeout(15_000) {
                    writer.join()
                    readers.forEach { it.join() }
                }

                assertEquals(targetUpdates, store.data.first())
                assertEquals(0, winFs.overlapCount.get())
            } finally {
                storeScope.coroutineContext.job.cancelAndJoin()
            }
        }

    @Test
    fun retryingAtomicMoveRetriesTransientAccessDeniedWithBackoff() {
        val root = createTempRoot()
        val source = root / "app_settings.pb.tmp"
        val target = root / "app_settings.pb"
        FileSystem.SYSTEM.write(source) { writeUtf8("updated") }
        FileSystem.SYSTEM.write(target) { writeUtf8("initial") }

        var attempts = 0
        val delays = mutableListOf<Long>()
        val flakyDelegate =
            object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun atomicMove(
                    source: Path,
                    target: Path,
                ) {
                    attempts++
                    if (attempts < 3) {
                        throw AccessDeniedException(source.toString(), target.toString(), "Transient lock")
                    }
                    super.atomicMove(source, target)
                }
            }

        val fs =
            RetryingAtomicMoveFileSystem(
                delegate = flakyDelegate,
                maxAttempts = 4,
                initialDelayMillis = 10L,
                sleeper = { delays += it },
            )
        fs.atomicMove(source, target)

        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), delays)
        assertEquals("updated", FileSystem.SYSTEM.read(target) { readUtf8() })
    }

    @Test
    fun retryingAtomicMoveRethrowsAfterMaxAttemptsAndDoesNotRetryOtherErrors() {
        val root = createTempRoot()
        val source = root / "a.tmp"
        val target = root / "a"

        var deniedAttempts = 0
        val deniedDelays = mutableListOf<Long>()
        val alwaysDenied =
            RetryingAtomicMoveFileSystem(
                delegate =
                    object : ForwardingFileSystem(FileSystem.SYSTEM) {
                        override fun atomicMove(
                            source: Path,
                            target: Path,
                        ) {
                            deniedAttempts++
                            throw AccessDeniedException(source.toString(), target.toString(), "Still locked")
                        }
                    },
                maxAttempts = 3,
                initialDelayMillis = 5L,
                sleeper = { deniedDelays += it },
            )
        assertFailsWith<AccessDeniedException> {
            alwaysDenied.atomicMove(source, target)
        }
        assertEquals(3, deniedAttempts)
        assertEquals(listOf(5L, 10L), deniedDelays)

        var otherAttempts = 0
        val otherDelays = mutableListOf<Long>()
        val otherFailure =
            RetryingAtomicMoveFileSystem(
                delegate =
                    object : ForwardingFileSystem(FileSystem.SYSTEM) {
                        override fun atomicMove(
                            source: Path,
                            target: Path,
                        ) {
                            otherAttempts++
                            throw IOException("Disk full")
                        }
                    },
                maxAttempts = 3,
                initialDelayMillis = 5L,
                sleeper = { otherDelays += it },
            )
        assertFailsWith<IOException> {
            otherFailure.atomicMove(source, target)
        }
        assertEquals(1, otherAttempts)
        assertEquals(emptyList(), otherDelays)
    }
}

private class WindowsReadLockedFileSystem(
    delegate: FileSystem,
) : ForwardingFileSystem(delegate) {
    private val openReads = ConcurrentHashMap<Path, AtomicInteger>()
    val overlapCount = AtomicInteger(0)

    override fun source(file: Path): Source {
        val delegateSource = super.source(file)
        val normalized = file.normalized()
        openReads.computeIfAbsent(normalized) { AtomicInteger(0) }.incrementAndGet()
        return object : ForwardingSource(delegateSource) {
            private val closed = AtomicBoolean(false)

            override fun close() {
                try {
                    super.close()
                } finally {
                    if (closed.compareAndSet(false, true)) {
                        openReads[normalized]?.decrementAndGet()
                    }
                }
            }
        }
    }

    override fun openReadOnly(file: Path): FileHandle {
        val delegateHandle = super.openReadOnly(file)
        val normalized = file.normalized()
        openReads.computeIfAbsent(normalized) { AtomicInteger(0) }.incrementAndGet()
        return object : FileHandle(readWrite = false) {
            private val handleClosed = AtomicBoolean(false)

            override fun protectedRead(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ): Int = delegateHandle.read(fileOffset, array, arrayOffset, byteCount)

            override fun protectedWrite(
                fileOffset: Long,
                array: ByteArray,
                arrayOffset: Int,
                byteCount: Int,
            ) {
                error("Read-only handle")
            }

            override fun protectedFlush() = Unit

            override fun protectedResize(size: Long) {
                error("Read-only handle")
            }

            override fun protectedSize(): Long = delegateHandle.size()

            override fun protectedClose() {
                try {
                    delegateHandle.close()
                } finally {
                    if (handleClosed.compareAndSet(false, true)) {
                        openReads[normalized]?.decrementAndGet()
                    }
                }
            }
        }
    }

    override fun atomicMove(
        source: Path,
        target: Path,
    ) {
        val readers = openReads[target.normalized()]?.get() ?: 0
        if (readers > 0) {
            overlapCount.incrementAndGet()
            throw AccessDeniedException(
                source.toString(),
                target.toString(),
                "Target file is open for reading ($readers active readers)",
            )
        }
        super.atomicMove(source, target)
    }
}
