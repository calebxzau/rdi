package calebxzhou.rdi.client

import calebxzau.rdi.common.logging.Loggers
import calebxzau.rdi.client.CONF
import calebxzhou.rdi.common.CommonConfig
import calebxzhou.rdi.common.ProxyConfig
import calebxzhou.rdi.common.serdesToml
import calebxzhou.rdi.common.service.ModService
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AppConfig(
    val preferModMirror: Boolean = true,
    val preferMcMirror: Boolean = true,
    //不限制
    val maxMemory: Int=0,
    val proxyConfig: ProxyConfig?=null,
    val pinyinName: Boolean = false,
    val solidWindow: Boolean = false,
){
    companion object {
        private val lgr by Loggers
        private val configFile: File
            get() = appConfigFile()
        fun load(): AppConfig {
            return if (configFile.exists()) {
                try {
                    serdesToml.decodeFromString(serializer(), configFile.readText())

                } catch (e: Exception) {
                    lgr.warn { "read config failed, use default and save" }
                    e.printStackTrace()
                    AppConfig().also { save(it) }
                }
            } else {
                AppConfig().also { save(it) }
            }.also {
                ModService.preferMirror = it.preferModMirror
                CommonConfig.updateProxyConfig(it.proxyConfig)
            }
        }

        fun save(config: AppConfig) {
            try {
                CONF = config
                ModService.preferMirror = config.preferModMirror
                CommonConfig.updateProxyConfig(config.proxyConfig)
                configFile.parentFile?.mkdirs()
                configFile.writeText(serdesToml.encodeToString(serializer(), config))
            } catch (e: Exception) {
                lgr.warn (e){ "save config failed\n" }
            }
        }
    }
}
