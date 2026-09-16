package calebxzhou.rdi.master

import calebxzhou.rdi.common.ProxyConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import java.io.File

@Serializable
data class DatabaseConfig(
    val host: String = "127.0.0.1",
    val port: Int = 27017,
    val name: String = "rdi5skypro"
)

@Serializable
data class ServerConfig(
    val port: Int = 65231,
    val httpsPort: Int = 65331,
    val bgpUrl: String = "bkrdi.calebxzhou.cn",
    val gameHost: String = "127.0.0.1",
    val remoteGameHost: String? = null,
    val gameStatusPort: Int = 65238
)

@Serializable
data class PostgresConfig(
    val jdbcUrl: String = "jdbc:postgresql://127.0.0.1:5432/rdi",
    val username: String = "rdi",
    val password: String = "",
    val maximumPoolSize: Int = 10
)

@Serializable
data class JwtConfig(
    val secret: String = "change-me",
    val issuer: String = "rdi",
    val audience: String = "rdi-clients",
    val realm: String = "RDI",
    val expiresInSeconds: Long = 7 * 24 * 3600, // default 7 days
)

@Serializable
data class DockerConfig(
    val host: String = "localhost",
    val port: Int = 2375,
    val tlsEnabled: Boolean = false,
    val tlsVerify: Boolean = false,
    val certPath: String = "",
    val keyPath: String = "",
    val caPath: String = "",
    val apiVersion: String = "1.41"
)
@Serializable
data class ApiKeyConfig(
    val curseforge: String = "",

)

@Serializable
data class DownloadConfig(
    val useMirror: Boolean = true
)

@Serializable
data class StorageConfig(
    val dlModsDir: String? = null,
    val dlModsClientDir: String? = null,
    val modpackDir: String? = null,
    val hostsDir: String? = null,
    val worldsDir: String? = null,
    val baseWorldDir: String? = null,
    val worldCacheDir: String? = null,
    val worldBackupDir: String? = null,
    val gameLibsDir: String? = null,
    val crashReportDir: String? = null,
    val host2Dir: String? = null
)

@Serializable
data class ImapConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 993,
    val username: String = "",
    val password: String = "",
    val folder: String = "INBOX",
    val ssl: Boolean = true,
    val startTls: Boolean = false,
    val connectionTimeoutMillis: Int = 10000,
    val timeoutMillis: Int = 10000,
    val pollIntervalSeconds: Int = 5,
    val operationSubject: String = "rdi-opr",
    val markAsSeenAfterHandle: Boolean = true
)

@Serializable
data class EmailConfig(
    val imap: ImapConfig = ImapConfig()
)

@Serializable
data class GameNodeRuleConfig(
    val id: Int,
    val name: String,
    val gameAddr: String,
    val provinces: List<String> = emptyList(),
    val carriers: List<String> = emptyList(),
    val matchOutsideChina: Boolean = false,
    val peekHourOnly: Boolean = false,
    val gameBackup: Boolean = false,
)

@Serializable
data class GameNodeConfig(
    val nodes: List<GameNodeRuleConfig> = emptyList()
)

@Serializable
data class AppConfig(
    val database: DatabaseConfig = DatabaseConfig(),
    val postgres: PostgresConfig = PostgresConfig(),
    val server: ServerConfig = ServerConfig(),
    val proxy: ProxyConfig = ProxyConfig(),
    val docker: DockerConfig = DockerConfig(),
    val apiKey: ApiKeyConfig = ApiKeyConfig(),
    val download: DownloadConfig = DownloadConfig(),
    val jwt: JwtConfig = JwtConfig(),
    val storage: StorageConfig = StorageConfig(),
    val email: EmailConfig = EmailConfig(),
    val gameNode: GameNodeConfig = GameNodeConfig(),
) {
    companion object {
        private val configFile = File("config.toml")
        private val lgr = KotlinLogging.logger {  }
        fun load(): Result<AppConfig> = runCatching {
            if (!configFile.exists()) {
                lgr.info { "config.toml不存在，创建默认配置" }
                AppConfig().also { configFile.writeText(Toml.encodeToString(serializer(), it)) }
            } else {
                require(configFile.isFile) { "config.toml不是文件: ${configFile.absolutePath}" }
                lgr.info { "find config.toml, loading" }
                Toml.decodeFromString(serializer(), configFile.readText())
            }
        }.onFailure { lgr.error(it) { "config.toml加载失败，拒绝启动" } }
    }
}
