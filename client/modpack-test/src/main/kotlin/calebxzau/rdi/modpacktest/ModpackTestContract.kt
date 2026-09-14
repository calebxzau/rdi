package calebxzau.rdi.modpacktest

import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModLoader
import calebxzau.rdi.common.model.Content
import java.io.File

enum class ModpackTestTarget {
    CLIENT,
    SERVER,
}

enum class ModpackTestStatus {
    NOT_RUN,
    RUNNING,
    PASSED,
    FAILED,
    STOPPED,
}

data class ModpackTestState(
    val status: ModpackTestStatus = ModpackTestStatus.NOT_RUN,
    val passSeconds: String? = null,
    val testedModsSignature: String? = null,
    val errorMessage: String? = null,
)

data class ModpackTestPaths(
    val workDir: File,
    val librariesDir: File,
)

/** Resolves the selected mods into files owned by the current test directory. */
fun interface ModpackTestModSourceResolver {
    suspend fun resolve(mods: List<Mod>, testDir: File): Result<File>
}

fun interface ModpackTestClientExtraResolver {
    suspend fun resolve(extras: List<Content>, testDir: File): Result<Unit>
}

class ModpackTestEnvironment(
    val paths: ModpackTestPaths,
    val launcher: ModpackTestLauncher,
)

interface ModpackTestLauncher {
    suspend fun prepareClientLoader(
        mcVersion: McVersion,
        loader: ModLoader,
        onProgress: (String) -> Unit,
    ): Result<Unit>

    suspend fun prepareClientLibraries(
        mcVersion: McVersion,
        versionId: String,
        versionDir: File,
        onProgress: (String) -> Unit,
    ): Result<Unit>

    fun launchClient(
        mcVersion: McVersion,
        versionId: String,
        versionDir: File,
        onLine: (String) -> Unit,
    ): Result<ModpackTestProcess>

    fun launchServer(
        mcVersion: McVersion,
        loaderVersion: ModLoader.Version,
        workDir: File,
        onLine: (String) -> Unit,
    ): Result<ModpackTestProcess>
}

interface ModpackTestProcess {
    fun isAlive(): Boolean

    fun stop(): Result<Unit>

    suspend fun waitFor(): Result<Int>
}
