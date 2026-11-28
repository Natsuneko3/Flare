package dev.dimension.flare.data.datastore

import androidx.datastore.core.InterProcessCoordinator
import androidx.datastore.core.ReadScope
import androidx.datastore.core.Storage
import androidx.datastore.core.StorageConnection
import androidx.datastore.core.WriteScope
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import dev.dimension.flare.data.io.FileStorage
import dev.dimension.flare.data.io.OkioFileStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal actual fun <T> createDataStoreStorage(
    name: String,
    serializer: OkioSerializer<T>,
    fileStorage: FileStorage,
): Storage<T> {
    val okioFileStorage =
        requireNotNull(fileStorage as? OkioFileStorage) {
            "Non-web DataStore storage requires OkioFileStorage"
        }
    return SerializedIoStorage(
        OkioStorage(
            fileSystem = okioFileStorage.fileSystem,
            serializer = serializer,
            producePath = {
                fileStorage.dataStoreFile(name)
            },
        ),
    )
}

/**
 * Serializes [readScope] and [writeScope] on the same [StorageConnection].
 *
 * DataStore's `OkioStorageConnection.readScope` uses `tryLock()` and continues opening the target
 * file even when a write is in progress. On Windows NTFS, an open read handle causes `atomicMove`
 * (`MoveFileExW`) in `writeScope` to fail with `AccessDeniedException`.
 */
private class SerializedIoStorage<T>(
    private val delegate: Storage<T>,
) : Storage<T> {
    override fun createConnection(): StorageConnection<T> = SerializedIoStorageConnection(delegate.createConnection())
}

private class SerializedIoStorageConnection<T>(
    private val delegate: StorageConnection<T>,
) : StorageConnection<T> {
    private val ioMutex = Mutex()

    override val coordinator: InterProcessCoordinator
        get() = delegate.coordinator

    override suspend fun <R> readScope(block: suspend ReadScope<T>.(locked: Boolean) -> R): R =
        ioMutex.withLock {
            delegate.readScope(block)
        }

    override suspend fun writeScope(block: suspend WriteScope<T>.() -> Unit) {
        ioMutex.withLock {
            delegate.writeScope(block)
        }
    }

    override fun close() {
        delegate.close()
    }
}
