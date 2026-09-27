package calebxzau.rdi.mcinstall

import calebxzhou.rdi.common.model.McVersion
import calebxzau.rdi.mclaunch.MinecraftArtifactDownloader
import calebxzhou.rdi.common.net.ktorClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream

data class McInstallDirectories(
    val mcDir: File,
    val versionsDir: File,
    val librariesDir: File,
    val assetsDir: File,
    val assetIndexesDir: File,
    val assetObjectsDir: File,
)

class McInstallEnvironment(
    val directories: McInstallDirectories,
    val preferMirror: () -> Boolean,
    val javaPath: () -> String,
    val resourceLoader: (String) -> Result<InputStream>,
    val launchPreparer: McLaunchPreparer,
    val fabricMetadataFetcher: suspend (String) -> Result<String> = ::fetchFabricMetadata,
    val fabricArtifactDownloader: MinecraftArtifactDownloader? = null,
)

private suspend fun fetchFabricMetadata(url: String): Result<String> = try {
    val response = ktorClient.get(url)
    check(response.status.value in 200..299) { "Fabric metadata request failed with HTTP ${response.status.value}: $url" }
    Result.success(response.bodyAsText())
} catch (cause: CancellationException) {
    throw cause
} catch (cause: Throwable) {
    Result.failure(cause)
}

fun interface McLaunchPreparer {
    suspend fun prepare(
        mcVersion: McVersion,
        loader: calebxzhou.rdi.common.model.ModLoader,
        versionId: String,
        versionDir: File,
        onProgress: (String) -> Unit,
    ): Result<Unit>
}

data class McLaunchPreparationRequest(
    val mcVersion: McVersion,
    val loader: calebxzhou.rdi.common.model.ModLoader,
    val versionId: String,
)
