package calebxzau.rdi.mclaunch

import calebxzau.rdi.mediaproc.MediaProcNativeRuntime
import calebxzau.rdi.mediaproc.MediaProcRuntimeClasspath
import java.io.File

data class MediaProcGameRuntime(
    val classpath: List<File>,
    val nativeLibraryDir: File,
)

object MediaProcGameClasspath {
    fun resolve(
        classpath: String = System.getProperty("java.class.path"),
        nativeRoot: File,
    ): Result<MediaProcGameRuntime> = runCatching {
        val files = MediaProcRuntimeClasspath.resolve(classpath).getOrThrow()
            .filterNot { it.isStandaloneKotlinRuntime }
            .distinctBy(File::getAbsolutePath)
        val nativeJar = MediaProcRuntimeClasspath.nativeJar(files)
        val nativeLibraryDir = MediaProcNativeRuntime.prepare(nativeJar, nativeRoot).getOrThrow()
        MediaProcGameRuntime(files, nativeLibraryDir)
    }

    private val File.isStandaloneKotlinRuntime: Boolean
        get() {
            val artifactName = name.removeSuffix(".jar").lowercase()
            return artifactName.startsWith("kotlinx-coroutines-") ||
                artifactName.startsWith("kotlinx-serialization-") ||
                KOTLIN_RUNTIME_ARTIFACTS.any { artifact ->
                    artifactName == artifact || artifactName.startsWith("$artifact-")
                }
        }

    private val KOTLIN_RUNTIME_ARTIFACTS = setOf(
        "kotlin-stdlib",
        "kotlin-stdlib-common",
        "kotlin-stdlib-jdk7",
        "kotlin-stdlib-jdk8",
        "kotlin-reflect",
    )
}
