package calebxzhou.rdi.client.service

import calebxzau.rdi.mclaunch.MediaProcGameClasspath
import java.io.File

internal typealias MediaProcGameRuntime = calebxzau.rdi.mclaunch.MediaProcGameRuntime

internal object MediaProcGameClasspath {
    fun resolve(
        classpath: String = System.getProperty("java.class.path"),
        nativeRoot: File = ClientDirs.toolsDir.resolve("mediaproc-natives"),
    ): Result<MediaProcGameRuntime> = MediaProcGameClasspath.resolve(
        classpath = classpath,
        nativeRoot = nativeRoot,
    )
}
