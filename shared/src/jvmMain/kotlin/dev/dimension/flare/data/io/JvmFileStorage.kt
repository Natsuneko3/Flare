package dev.dimension.flare.data.io

import dev.dimension.flare.common.FileSystemUtilsExt
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import org.koin.core.annotation.Single
import java.nio.file.AccessDeniedException

@Single(binds = [FileStorage::class, AppFileStore::class])
internal class JvmFileStorage :
    OkioFileStorage(
        fileSystem = jvmPlatformFileSystem(),
        cacheRoot = FileSystemUtilsExt.flareCacheDirectory().toOkioPath(),
        root = FileSystemUtilsExt.flareDirectory().toOkioPath(),
    )

internal fun jvmPlatformFileSystem(): FileSystem =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        RetryingAtomicMoveFileSystem(FileSystem.SYSTEM)
    } else {
        FileSystem.SYSTEM
    }

/**
 * Retries [atomicMove] on transient Windows `AccessDeniedException` (e.g., short-lived file locks
 * held by Windows Defender or search indexing after a file write/close).
 */
internal class RetryingAtomicMoveFileSystem(
    delegate: FileSystem,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : ForwardingFileSystem(delegate) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1" }
        require(initialDelayMillis >= 0) { "initialDelayMillis must be >= 0" }
    }

    override fun atomicMove(
        source: Path,
        target: Path,
    ) {
        var delayMillis = initialDelayMillis
        var attempt = 1
        while (true) {
            try {
                super.atomicMove(source, target)
                return
            } catch (error: AccessDeniedException) {
                if (attempt >= maxAttempts) {
                    throw error
                }
                try {
                    sleeper(delayMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    error.addSuppressed(interrupted)
                    throw error
                }
                delayMillis = (delayMillis * 2).coerceAtMost(MAX_DELAY_MILLIS)
                attempt++
            }
        }
    }

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 8
        const val DEFAULT_INITIAL_DELAY_MILLIS = 10L
        const val MAX_DELAY_MILLIS = 200L
    }
}
