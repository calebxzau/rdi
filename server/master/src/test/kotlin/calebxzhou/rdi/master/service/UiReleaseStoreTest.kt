package calebxzhou.rdi.master.service

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.serdesJson
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UiReleaseStoreTest {
    private val rootDir: File = Files.createTempDirectory("ui-release-store").toFile()
    private val store = UiReleaseStore(rootDir)

    private val sharedHash = "a".repeat(40)
    private val windowsHash = "b".repeat(40)
    private val linuxHash = "c".repeat(40)

    @AfterTest
    fun cleanUp() {
        rootDir.deleteRecursively()
    }

    private fun writeRelease(version: String, manifests: Map<String, Map<String, String>>) {
        val versionDir = rootDir.resolve(version).also { it.mkdirs() }
        ZipOutputStream(versionDir.resolve(UiReleaseStore.ARCHIVE_NAME).outputStream()).use { zip ->
            manifests.values.flatMap { it.keys }.toSortedSet().forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(name.toByteArray())
                zip.closeEntry()
            }
        }
        manifests.forEach { (platform, manifest) ->
            versionDir.resolve("$platform.json").writeText(serdesJson.encodeToString(manifest))
        }
    }

    private fun writeDefaultRelease(version: String = "6.7.0") {
        writeRelease(
            version,
            mapOf(
                UiReleaseStore.WINDOWS_X64 to mapOf(
                    "rdi-ui.jar" to sharedHash,
                    "skiko-awt-runtime-windows-x64.jar" to windowsHash,
                ),
                "linux-x64" to mapOf(
                    "rdi-ui.jar" to sharedHash,
                    "skiko-awt-runtime-linux-x64.jar" to linuxHash,
                ),
            )
        )
    }

    @Test
    fun `latest release reads the platform manifest from the version directory`() {
        writeDefaultRelease()
        rootDir.resolve(UiReleaseStore.LATEST_FILE_NAME).writeText("6.7.0\n")

        val windows = store.loadLatest(UiReleaseStore.WINDOWS_X64)
        assertEquals("6.7.0", windows.version)
        assertEquals(rootDir.resolve("6.7.0/release.zip"), windows.archive)
        assertEquals(
            mapOf("rdi-ui.jar" to sharedHash, "skiko-awt-runtime-windows-x64.jar" to windowsHash),
            windows.manifest
        )

        val linux = store.load("6.7.0", "linux-x64")
        assertEquals(setOf("rdi-ui.jar", "skiko-awt-runtime-linux-x64.jar"), linux.manifest.keys)
    }

    @Test
    fun `files of another platform cannot be downloaded through this platform`() {
        writeDefaultRelease()
        val windows = store.load("6.7.0", UiReleaseStore.WINDOWS_X64)

        store.requireLibrary(windows, "skiko-awt-runtime-windows-x64.jar")
        assertFailsWith<RequestError> { store.requireLibrary(windows, "skiko-awt-runtime-linux-x64.jar") }
    }

    @Test
    fun `missing platform manifest is rejected`() {
        writeDefaultRelease()
        assertFailsWith<RequestError> { store.load("6.7.0", "macos-arm64") }
    }

    @Test
    fun `missing latest pointer or archive is rejected`() {
        assertFailsWith<RequestError> { store.loadLatest(UiReleaseStore.WINDOWS_X64) }

        writeDefaultRelease()
        rootDir.resolve("6.7.0/release.zip").delete()
        assertFailsWith<RequestError> { store.load("6.7.0", UiReleaseStore.WINDOWS_X64) }
    }

    @Test
    fun `version and platform cannot escape the release directory`() {
        writeDefaultRelease()
        assertFailsWith<RequestError> { store.load("../6.7.0", UiReleaseStore.WINDOWS_X64) }
        assertFailsWith<RequestError> { store.load("6.7.0", "../windows-x64") }
        assertFailsWith<RequestError> { store.load("6.7.0", "windows-x64/..") }
    }

    @Test
    fun `unsafe library names are rejected`() {
        writeDefaultRelease()
        val windows = store.load("6.7.0", UiReleaseStore.WINDOWS_X64)
        listOf("", ".", "..", "../rdi-ui.jar", "a/b.jar", "a\\b.jar", "C:rdi-ui.jar").forEach { name ->
            assertFailsWith<RequestError>(name) { store.requireLibrary(windows, name) }
        }
    }

    @Test
    fun `manifest with unsafe entries or bad hashes is rejected`() {
        writeRelease("6.7.1", mapOf(UiReleaseStore.WINDOWS_X64 to mapOf("../rdi-ui.jar" to sharedHash)))
        assertFailsWith<RequestError> { store.load("6.7.1", UiReleaseStore.WINDOWS_X64) }

        writeRelease("6.7.2", mapOf(UiReleaseStore.WINDOWS_X64 to mapOf("rdi-ui.jar" to "not-a-sha1")))
        assertFailsWith<RequestError> { store.load("6.7.2", UiReleaseStore.WINDOWS_X64) }

        writeRelease("6.7.3", mapOf(UiReleaseStore.WINDOWS_X64 to mapOf("readme.txt" to sharedHash)))
        assertFailsWith<RequestError> { store.load("6.7.3", UiReleaseStore.WINDOWS_X64) }
    }

    @Test
    fun `script routes serve only linux and macos arm64`() {
        assertEquals("linux-x64", UiReleaseStore.requireScriptPlatform("linux-x64"))
        assertEquals("macos-arm64", UiReleaseStore.requireScriptPlatform("macos-arm64"))
        listOf(UiReleaseStore.WINDOWS_X64, "macos-x64", "linux-arm64", "", "../linux-x64").forEach { id ->
            assertFailsWith<RequestError>(id) { UiReleaseStore.requireScriptPlatform(id) }
        }
    }

    @Test
    fun `text manifest lists every file as sorted hash and name lines`() {
        writeDefaultRelease()
        val linux = store.load("6.7.0", "linux-x64")
        assertEquals(
            "$sharedHash rdi-ui.jar\n$linuxHash skiko-awt-runtime-linux-x64.jar\n",
            UiReleaseStore.manifestText(linux.manifest)
        )
        assertEquals("${"a".repeat(40)} rdi-ui.jar\n", UiReleaseStore.manifestText(mapOf("rdi-ui.jar" to "A".repeat(40))))
    }

    @Test
    fun `manifest names that would break line based parsing are rejected`() {
        writeRelease("6.7.4", mapOf(UiReleaseStore.WINDOWS_X64 to mapOf("rdi-ui.jar " to sharedHash)))
        assertFailsWith<RequestError> { store.load("6.7.4", UiReleaseStore.WINDOWS_X64) }

        writeRelease("6.7.5", mapOf(UiReleaseStore.WINDOWS_X64 to mapOf("rdi-ui.jar\nx.jar" to sharedHash)))
        assertFailsWith<RequestError> { store.load("6.7.5", UiReleaseStore.WINDOWS_X64) }
    }
}
