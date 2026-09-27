package calebxzau.rdi.mclaunch

import java.io.File

object RdiZstdRuntime {
    fun resolve(): Result<List<File>> = runCatching {
        val file = File(
            Class.forName("com.github.luben.zstd.Zstd")
                .protectionDomain.codeSource.location.toURI()
        ).absoluteFile
        check(file.isFile && file.extension.equals("jar", ignoreCase = true)) {
            "RDI Zstd运行库不存在或不是JAR: ${file.absolutePath}"
        }
        listOf(file)
    }
}
