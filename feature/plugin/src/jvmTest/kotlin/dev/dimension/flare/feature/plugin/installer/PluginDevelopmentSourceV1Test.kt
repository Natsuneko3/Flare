package dev.dimension.flare.feature.plugin.installer

import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Source
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PluginDevelopmentSourceV1Test {
    private lateinit var root: Path
    private val files = FileSystem.SYSTEM

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("plugin-development-source").toOkioPath()
        TestFppFactory.validEntries().forEach { (name, bytes) ->
            val path = root / name
            files.createDirectories(path.parent!!)
            files.write(path) { write(bytes) }
        }
    }

    @AfterTest
    fun teardown() {
        files.deleteRecursively(root)
    }

    @Test
    fun hashesOnlyPluginInputsAndKeepsAnImmutableSnapshot() {
        val first = PluginDevelopmentSourceV1.read(files, root)
        files.write(root / "package.json") { writeUtf8("ignored build metadata") }
        assertEquals(first.hash, PluginDevelopmentSourceV1.read(files, root).hash)
        files.write(root / "plugin.js") { writeUtf8("changed") }
        assertNotEquals(first.hash, PluginDevelopmentSourceV1.read(files, root).hash)
        assertEquals(
            TestFppFactory.validScript,
            first.archive.files
                .getValue("plugin.js")
                .decodeToString(),
        )
        files.createDirectories(root / "locales")
        val scriptHash = PluginDevelopmentSourceV1.read(files, root).hash
        files.write(root / "locales/en.json") { writeUtf8("{}") }
        assertNotEquals(scriptHash, PluginDevelopmentSourceV1.read(files, root).hash)
    }

    @Test
    fun rejectsMissingFilesAndSymlinks() {
        val script = root / "plugin.js"
        files.delete(script)
        assertFails { PluginDevelopmentSourceV1.read(files, root) }
        files.write(script) { writeUtf8(TestFppFactory.validScript) }
        files.write(root / "elsewhere.js") { writeUtf8(TestFppFactory.validScript) }
        val valid = PluginDevelopmentSourceV1.read(files, root)
        assertEquals(
            TestFppFactory.validScript,
            valid.archive.files
                .getValue("plugin.js")
                .decodeToString(),
        )

        // Model link metadata without requiring Windows symbolic link privileges.
        var openedSymlink = false
        val linkedFiles =
            object : ForwardingFileSystem(files) {
                override fun metadataOrNull(path: Path): FileMetadata? {
                    val metadata = super.metadataOrNull(path)
                    return if (path == script) metadata?.copy(symlinkTarget = root / "elsewhere.js") else metadata
                }

                override fun source(file: Path): Source {
                    if (file == script) openedSymlink = true
                    return super.source(file)
                }
            }
        val error = assertFailsWith<IllegalArgumentException> { PluginDevelopmentSourceV1.read(linkedFiles, root) }
        assertEquals("Plugin files must not be symbolic links", error.message)
        assertFalse(openedSymlink)
    }

    @Test
    fun rejectsOversizedInputsBeforeReadingThem() {
        java.io.RandomAccessFile((root / "manifest.json").toString(), "rw").use { it.setLength(16 * 1024 * 1024) }
        assertFails { PluginDevelopmentSourceV1.read(files, root) }
    }
}
