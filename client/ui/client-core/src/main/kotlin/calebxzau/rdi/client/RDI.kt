package calebxzau.rdi.client

import calebxzhou.rdi.client.AppConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.io.InputStream
/** Global configuration and logger initialized by the launcher entry point. */
@Volatile
var CONF = AppConfig.load()
val lgr = KotlinLogging.logger("RDI")

object RDIClient {
    val DIR: File = File(System.getProperty("user.dir")).absoluteFile
    var OLD_MAIN = false
    init {
        lgr.info { "RDI启动中" }
        DIR.mkdir()
        lgr.info { (javaClass.protectionDomain.codeSource.location.toURI().toString()) }
        System.setProperty("compose.interop.blending", "true")
    }

    fun jarResource(name: String): InputStream = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
        "找不到内置资源：$name"
    }

}
