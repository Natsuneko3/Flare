package dev.dimension.flare.common

import dev.dimension.flare.Res
import dev.dimension.flare.copied_to_clipboard
import dev.dimension.flare.data.network.applyMedia
import dev.dimension.flare.data.network.ktorClient
import dev.dimension.flare.manga_reader_copy_page_failed
import dev.dimension.flare.media_download_started
import dev.dimension.flare.media_save_fail
import dev.dimension.flare.media_save_success
import dev.dimension.flare.ui.component.ComposeInAppNotification
import dev.dimension.flare.ui.model.UiMedia
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.Closeable
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

@Single
internal class DesktopDownloadManager(
    private val inAppNotification: ComposeInAppNotification,
    private val client: HttpClient = ktorClient(),
) : Closeable {
    suspend fun download(
        media: UiMedia,
        targetFile: File,
        overwrite: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {},
        notify: Boolean = true,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val url = media.urlForDownload
            require(url.isNotBlank()) { "url must not be blank" }
            require(targetFile.path.isNotBlank()) { "targetFile must not be blank" }
            if (targetFile.exists() && !overwrite) {
                throw IllegalStateException("Target file already exists: ${targetFile.absolutePath}")
            }

            targetFile.parentFile?.mkdirs()
            val tempFile = File(targetFile.absolutePath + ".part")
            tempFile.parentFile?.mkdirs()
            if (tempFile.exists()) {
                tempFile.delete()
            }

            try {
                val resourcePath = url.substringBefore('?').substringBefore('#').lowercase()
                require(!resourcePath.endsWith(".m3u8") && !resourcePath.endsWith(".mpd") && !resourcePath.endsWith(".ism/manifest")) {
                    "Adaptive streams require a downloadable media URL"
                }
                if (notify) {
                    inAppNotification.message(Res.string.media_download_started)
                }
                if (url.startsWith("file://")) {
                    require(isImageTranslationFile(media, FileSystemUtilsExt.flareCacheDirectory())) { "Unsupported local media" }
                    File(java.net.URI(url)).copyTo(tempFile, overwrite = true)
                } else {
                    client
                        .prepareGet(url) {
                            applyMedia(media)
                        }.execute { response ->
                            check(response.status.isSuccess()) { "Media download failed: HTTP ${response.status.value}" }
                            writeResponseToFile(
                                response = response,
                                outputFile = tempFile,
                                onProgress = onProgress,
                            )
                        }
                }
                moveIntoPlace(tempFile = tempFile, targetFile = targetFile, overwrite = overwrite)
                if (notify) {
                    inAppNotification.message(Res.string.media_save_success)
                }
                true
            } catch (t: Exception) {
                if (notify) {
                    inAppNotification.message(
                        Res.string.media_save_fail,
                        success = false,
                    )
                }
                tempFile.delete()
                false
            }
        }

    suspend fun copyImageToClipboard(
        media: UiMedia,
        fileName: String,
        clipboard: Clipboard? = null,
    ) = withContext(Dispatchers.IO) {
        var file: File? = null
        var copied = false
        try {
            val target = Files.createTempFile("flare-", "-$fileName").toFile()
            file = target
            val export = prepareImageTranslationExport(media, FileSystemUtilsExt.flareCacheDirectory())
            if (download(export, target, notify = false)) {
                val selection = ClipboardImageFile(target)
                (clipboard ?: Toolkit.getDefaultToolkit().systemClipboard).setContents(selection) { _, _ -> target.delete() }
                target.deleteOnExit()
                copied = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A busy system clipboard or an unavailable download leaves the previous clipboard intact.
        } finally {
            if (!copied) file?.delete()
        }
        inAppNotification.message(
            if (copied) Res.string.copied_to_clipboard else Res.string.manga_reader_copy_page_failed,
            success = copied,
        )
    }

    suspend fun downloadAll(
        mediaByFileName: Map<String, UiMedia>,
        targetDirectory: File,
        overwrite: Boolean = true,
    ): MediaDownloadBatchResult {
        val succeededFileNames = mutableListOf<String>()
        val failedFileNames = mutableListOf<String>()
        mediaByFileName.filterValues { it !is UiMedia.Ugoira }.forEach { (fileName, media) ->
            val targetFile =
                File(
                    targetDirectory,
                    MediaFileNamePolicy.safeLocalFileName(fileName, fallback = "media"),
                )
            val success =
                runCatching {
                    download(
                        media = media,
                        targetFile = targetFile,
                        overwrite = overwrite,
                        notify = false,
                    )
                }.getOrDefault(false)
            if (success) {
                succeededFileNames += fileName
            } else {
                failedFileNames += fileName
            }
        }
        return MediaDownloadBatchResult(
            succeededFileNames = succeededFileNames,
            failedFileNames = failedFileNames,
        )
    }

    override fun close() {
        client.close()
    }
}

private class ClipboardImageFile(
    private val file: File,
) : Transferable {
    private val flavors =
        if (ImageIO.createImageInputStream(file).use { ImageIO.getImageReaders(it).hasNext() }) {
            arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.imageFlavor)
        } else {
            arrayOf(DataFlavor.javaFileListFlavor)
        }

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors.copyOf()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in flavors

    override fun getTransferData(flavor: DataFlavor): Any =
        when {
            flavor == DataFlavor.javaFileListFlavor -> listOf(file)
            flavor == DataFlavor.imageFlavor && isDataFlavorSupported(flavor) -> ImageIO.read(file)
            else -> throw UnsupportedFlavorException(flavor)
        }
}

internal data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() =
            totalBytes
                ?.takeIf { it > 0 }
                ?.let { bytesDownloaded.toFloat() / it.toFloat() }
}

private suspend fun writeResponseToFile(
    response: HttpResponse,
    outputFile: File,
    onProgress: (DownloadProgress) -> Unit,
) {
    val totalBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val channel = response.body<ByteReadChannel>()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var downloaded = 0L

    outputFile.outputStream().buffered().use { output ->
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read <= 0) {
                break
            }
            output.write(buffer, 0, read)
            downloaded += read
            onProgress(
                DownloadProgress(
                    bytesDownloaded = downloaded,
                    totalBytes = totalBytes,
                ),
            )
        }
        channel.closedCause?.let { throw it }
        output.flush()
    }
}

private fun moveIntoPlace(
    tempFile: File,
    targetFile: File,
    overwrite: Boolean,
) {
    try {
        if (overwrite) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } else {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    } catch (_: AtomicMoveNotSupportedException) {
        if (overwrite) {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        } else {
            Files.move(tempFile.toPath(), targetFile.toPath())
        }
    }
}
