package dev.dimension.flare

import dev.dimension.flare.common.FileItem
import dev.dimension.flare.common.FileType
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import kotlin.uuid.Uuid

internal actual fun createTestRootPath(): Path =
    "${NSTemporaryDirectory()}draft-media-store-test-${Uuid.random()}".toPath().also {
        FileSystem.SYSTEM.createDirectories(it)
    }

internal actual fun deleteTestRootPath(path: Path) {
    repeat(5) { attempt ->
        if (!FileSystem.SYSTEM.exists(path)) return
        try {
            FileSystem.SYSTEM.deleteRecursively(path)
            return
        } catch (_: IOException) {
            if (attempt == 4) return
            NSThread.sleepForTimeInterval(0.01)
        }
    }
}

internal actual fun createTestFileSystem(): FileSystem = FileSystem.SYSTEM

internal actual fun createTestFileItem(
    root: Path,
    name: String?,
    bytes: ByteArray,
    type: FileType,
): FileItem {
    val path = root.resolve("source_${Uuid.random()}.bin")
    FileSystem.SYSTEM.createDirectories(path.parent!!)
    FileSystem.SYSTEM.write(path) {
        write(bytes)
    }
    return FileItem(
        name = name,
        data = bytes,
        type = type,
    )
}
