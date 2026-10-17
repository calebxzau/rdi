package calebxzau.rdi.mcinstall

import kotlin.test.Test
import kotlin.test.assertEquals

class McInstallSourcePlanTest {
    private val neoformPath = "net/neoforged/neoform/1.21.1-20240808.144430/neoform-1.21.1-20240808.144430.zip"
    private val official = "https://maven.neoforged.net/releases/$neoformPath"
    private val mirror = "https://bmclapi2.bangbang93.com/maven/$neoformPath"
    private val sha1 = "0123456789abcdef0123456789abcdef01234567"
    private val rdi = "https://rdi.example:65331/game-lib/maven/$neoformPath?sha1=$sha1"

    @Test
    fun rdiSourceComesAfterMirrorAndOfficial() {
        val plan = createTestMcInstall(preferMirror = true, rdiServerUrl = "https://rdi.example:65331")
            .buildDownloadSourcePlan(official, sha1 = sha1)

        assertEquals(listOf(mirror, official, rdi), plan.urls)
    }

    @Test
    fun rdiSourceStaysLastWhenOfficialIsPreferred() {
        val plan = createTestMcInstall(preferMirror = false, rdiServerUrl = "https://rdi.example:65331")
            .buildDownloadSourcePlan(official, sha1 = sha1)

        assertEquals(listOf(official, mirror, rdi), plan.urls)
    }

    @Test
    fun nonLibraryDownloadsHaveNoRdiSource() {
        val clientJar = "https://piston-data.mojang.com/v1/objects/abc/client.jar"
        val plan = createTestMcInstall(rdiServerUrl = "https://rdi.example:65331")
            .buildDownloadSourcePlan(clientJar, mirrorUrl = "https://bmclapi2.bangbang93.com/version/1.21.1/client")

        assertEquals(emptyList(), plan.lastResortUrls)
    }

    @Test
    fun noRdiSourceWithoutServer() {
        val plan = createTestMcInstall(rdiServerUrl = null).buildDownloadSourcePlan(official, sha1 = sha1)

        assertEquals(listOf(official, mirror), plan.urls)
    }
}
