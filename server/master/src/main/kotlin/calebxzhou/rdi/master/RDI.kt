package calebxzhou.rdi.master

import calebxzhou.rdi.common.CommonConfig
import calebxzhou.rdi.common.DEBUG
import calebxzhou.rdi.common.DL_MOD_DIR
import calebxzhou.rdi.common.exception.RequestError
// Host2 is archived; its client-pack error handler is intentionally disabled.
import calebxzhou.rdi.common.model.Modpack
import calebxzhou.rdi.common.service.ModService
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.common.util.exportFromJarResource
import calebxzhou.rdi.master.exception.AuthError
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.response
import calebxzhou.rdi.master.net.isClientDisconnect
import calebxzhou.rdi.master.service.*
import calebxzhou.rdi.master.service.PlayerService.accountCol
import calebxzhou.rdi.master.service.host.HostPresenceService
import calebxzhou.rdi.master.service.host.HostService
import calebxzhou.rdi.master.service.host.hostPlayRoutes
import calebxzhou.rdi.master.service.host.hostRoutes
import calebxzhou.rdi.master.service.modpack.ModpackUploadService
import calebxzhou.rdi.master.service.modpack.ModpackBuildService
import calebxzau.rdi.server.infra.configurePostgresServices
import calebxzau.rdi.server.infra.productionMongoCodecRegistry
import calebxzau.rdi.server.service.baseworld.baseWorldRoutes
import calebxzhou.rdi.common.service.McServerPlayers
import calebxzhou.rdi.common.service.McServerPlayerSample
import calebxzhou.rdi.common.util.toUUID
import calebxzau.rdi.master.service.GameStatusService
// Archived Modpack2 and friend routes are intentionally disabled.
import calebxzhou.rdi.master.ygg.YggdrasilService.yggdrasilRoutes
import com.mongodb.MongoClientSettings
import com.mongodb.ServerAddress
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.kotlin.client.coroutine.MongoClient
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.compression.zstd.zstd
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bson.UuidRepresentation
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

val CONF = AppConfig.load().getOrThrow()
val lgr = KotlinLogging.logger { }
val DB = MongoClient.create(
    MongoClientSettings.builder()
        .applyToClusterSettings { builder ->
            builder.hosts(listOf(ServerAddress(CONF.database.host, CONF.database.port)))
        }
        .uuidRepresentation(UuidRepresentation.STANDARD)
        .codecRegistry(productionMongoCodecRegistry())
        .build()).getDatabase(CONF.database.name)

private fun storageDir(path: String?, defaultName: String): File {
    val trimmed = path?.trim().orEmpty()
    return if (trimmed.isBlank()) File(defaultName) else File(trimmed)
}



val CRASH_REPORT_DIR = storageDir(CONF.storage.crashReportDir, "crash-report")
val MODPACK_DATA_DIR = storageDir(CONF.storage.modpackDir, "modpack")
val HOSTS_DIR = storageDir(CONF.storage.hostsDir, "hosts")
val Host2Dir = storageDir(CONF.storage.host2Dir, "host2")
val GAME_LIBS_DIR = storageDir(CONF.storage.gameLibsDir, "game-libs")
val BaseWorldDir = storageDir(CONF.storage.baseWorldDir, "base-world")
val WORLDS_DIR = storageDir(CONF.storage.worldsDir, "worlds")
val WORLD_CACHE_DIR = storageDir(CONF.storage.worldCacheDir, "world-cache")
val WORLD_BACKUP_DIR = storageDir(CONF.storage.worldBackupDir, "world-backup").also { it.mkdirs() }
val DL_MODS_CLIENT_DIR = storageDir(CONF.storage.dlModsClientDir, "dl-mods-client")

class RDI {}

fun main(): Unit = runBlocking {
    if (DEBUG) {
        System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
    }
    CONF.storage.dlModsDir
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { System.setProperty("rdi.modDir", it) }
    ModStorage.prepareDirectories(DL_MOD_DIR, DL_MODS_CLIENT_DIR).getOrThrow()
    CommonConfig.updateProxyConfig(CONF.proxy)
    ModService.preferMirror = CONF.download.useMirror

    CRASH_REPORT_DIR.mkdirs()
    MODPACK_DATA_DIR.mkdirs()
    HOSTS_DIR.mkdirs()
    // HOST2_DIR.mkdirs()
    if ((!GAME_LIBS_DIR.exists() && !GAME_LIBS_DIR.mkdirs()) || !GAME_LIBS_DIR.isDirectory) {
        throw IllegalStateException("Unable to prepare game libraries directory: ${GAME_LIBS_DIR.absolutePath}")
    }
    GAME_LIBS_DIR.resolve("mc-log4j2.xml").exportFromJarResource("mc_log4j2.xml")
    WORLDS_DIR.mkdirs()
    WORLD_CACHE_DIR.mkdirs()
    ModpackUploadService.cleanupStaleUploadsOnStartup()
    val uploadSessionCleanupJob = launch(Dispatchers.IO) {
        while (isActive) {
            delay(1.hours)
            ModpackUploadService.cleanupExpiredUploadSessions()
        }
    }
    lgr.info { "worlds: ${WORLDS_DIR.absolutePath}" }
    lgr.info { "world cache: ${WORLD_CACHE_DIR.absolutePath}" }
    lgr.info { "world bkup: ${WORLD_BACKUP_DIR.absolutePath}" }
    lgr.info { "init db" }

    accountCol.createIndex(Indexes.ascending("qq"), IndexOptions().unique(true))
    accountCol.createIndex(Indexes.ascending("name"), IndexOptions().unique(true))
    ModpackService.dbcl.createIndex(Indexes.ascending(Modpack::name.name), IndexOptions().unique(true))
    ModpackService.dbcl.createIndex(Indexes.ascending(Modpack::categories.name))
    ModpackService.dbcl.createIndex(Indexes.ascending(Modpack::mcVer.name))
    ModpackService.dbcl.createIndex(Indexes.descending(Modpack::playCount.name))
    DownloadQuotaService.ensureIndexes()

    ModpackBuildService.recoverUnfinishedVersionBuildsOnStartup()
    launch(Dispatchers.IO) {
        UnusedModPurgeService.purgeOnStartup()
    }
    HostPresenceService.startIdleMonitor()
    require(CONF.server.gameStatusPort in 1..65535) {
        "server.gameStatusPort must be between 1 and 65535"
    }
    val gameStatusService = GameStatusService(port = CONF.server.gameStatusPort, playersProvider = {
        val ids = HostPresenceService.getAllHostsOnlinePlayerIds().distinct()
        val accounts = if (ids.isEmpty()) emptyMap() else PlayerService.getByIds(ids).associateBy { it._id }
        McServerPlayers(
            max = maxOf(ids.size, 88888),
            online = ids.size,
            sample = ids.mapNotNull { id ->
                accounts[id]?.let { account ->
                    McServerPlayerSample(name = account.name, id = account._id.toUUID().toString())
                }
            }
        )
    })
    Runtime.getRuntime().addShutdownHook(Thread {
        lgr.info { "Application shutdown initiated..." }
        gameStatusService.close()
        EmailService.shutdown()
        HostService.shutdown()
        lgr.info { "Application shutdown complete" }
    })
    // Start server with HTTP and optionally HTTPS on the same port
    try {
        gameStatusService.start().getOrThrow()
        startServer()
    } finally {
        gameStatusService.close()
        uploadSessionCleanupJob.cancelAndJoin()
    }

}

fun startServer() {
    val certPath = System.getProperty("rdi.cert")
    val keyPath = System.getProperty("rdi.key")

    // Check if SSL should be enabled
    val sslEnabled = certPath != null
    var keyStore: KeyStore? = null

    if (sslEnabled) {
        val certFile = File(certPath!!)
        // Use explicit key path if provided, otherwise derive from cert path
        val keyFile = if (keyPath != null) {
            File(keyPath)
        } else {
            File(certPath.replace("-cert.pem", "-key.pem"))
        }

        if (!certFile.exists()) {
            lgr.warn { "Certificate file not found: $certPath, starting HTTP only" }
        } else if (!keyFile.exists()) {
            lgr.warn { "Key file not found: ${keyFile.absolutePath}, starting HTTP only" }
        } else {
            try {
                keyStore = createKeyStoreFromPem(certFile, keyFile)
                lgr.info { "SSL enabled with cert: $certPath, key: ${keyFile.absolutePath}" }
            } catch (e: Exception) {
                lgr.error { "Failed to load SSL certificate, starting HTTP only" + "\n" + e }
            }
        }
    }

    // Start server with conditional SSL
    embeddedServer(Netty, configure = {
        enableHttp2 = true
        // HTTP connector
        connector {
            host = "::"
            port = CONF.server.port
        }
        // HTTPS connector (only if keyStore is available) - same port
        if (keyStore != null) {
            sslConnector(
                keyStore = keyStore,
                keyAlias = "rdiserver",
                keyStorePassword = { "changeit".toCharArray() },
                privateKeyPassword = { "changeit".toCharArray() }
            ) {
                host = "::"
                port = CONF.server.httpsPort
            }
        }
    }) {
        configureServer()
    }.start(wait = true)

    if (keyStore != null) {
        lgr.info { "Server started with HTTP on port ${CONF.server.port} and HTTPS/HTTP2 on port ${CONF.server.httpsPort}" }
    } else {
        lgr.info { "Server started with HTTP only on port ${CONF.server.port}" }
    }
}

private fun createKeyStoreFromPem(certFile: File, keyFile: File): KeyStore {
    // Add BouncyCastle provider
    Security.addProvider(BouncyCastleProvider())

    // Read full certificate chain (leaf + intermediates)
    val certFactory = CertificateFactory.getInstance("X.509")
    val certChain = FileInputStream(certFile).use { fis ->
        certFactory.generateCertificates(fis)
            .map { it as X509Certificate }
            .toTypedArray()
    }
    if (certChain.isEmpty()) {
        throw IllegalArgumentException("No certificates found in: ${certFile.absolutePath}")
    }
    lgr.info { "Loaded ${certChain.size} certificate(s) from chain" }

    // Read private key using BouncyCastle PEMParser
    val privateKey: PrivateKey = FileReader(keyFile).use { reader ->
        val pemParser = PEMParser(reader)
        val pemObject = pemParser.readObject()
        val converter = JcaPEMKeyConverter().setProvider("BC")

        when (pemObject) {
            is PEMKeyPair -> {
                // PKCS#1 format (RSA PRIVATE KEY)
                converter.getPrivateKey(pemObject.privateKeyInfo)
            }
            is PrivateKeyInfo -> {
                // PKCS#8 format (PRIVATE KEY)
                converter.getPrivateKey(pemObject)
            }
            else -> {
                throw IllegalArgumentException("Unsupported PEM object type: ${pemObject?.javaClass?.name}")
            }
        }
    }

    // Create KeyStore with full certificate chain
    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
    keyStore.load(null, null)
    keyStore.setKeyEntry(
        "rdiserver",
        privateKey,
        "changeit".toCharArray(),
        certChain
    )

    return keyStore
}

private fun Application.configureServer() {
    configurePostgresServices()
    EmailService.startListener()
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.response<Unit>(-404, "找不到请求的内容", null, status)
        }
        //参数不全/有问题
        exception<ParamError> { call, cause ->
            call.response<Unit>(false, cause.message ?: "参数错误", null)
        }
        // Host2ClientPackUnavailable is archived with Host2.
        //逻辑错误
        exception<RequestError> { call, cause ->
            call.response<Unit>(
                code = -1,
                msg = cause.message ?: "逻辑错误",
                errorCode = cause.errorCode,
                currentRevision = cause.currentRevision,
            )
        }
        //认证错误
        exception<AuthError> { call, cause ->
            call.response<Unit>(-401, cause.message ?: "账密错/未登录", null, HttpStatusCode.Unauthorized)
        }

        //其他内部错误
        exception<Throwable> { call, cause ->
            if (!cause.isClientDisconnect()) {
                cause.printStackTrace()
                call.response<Unit>(-500, "服务器内部错误", null)
            }
        }
    }
    install(ContentNegotiation) {
        json(serdesJson) // Apply the custom Json configuration
    }

    install(Authentication) {
        val jwtConfig = CONF.jwt
        jwt("auth-jwt") {
            realm = jwtConfig.realm
            verifier(JwtService.verifier)
            validate { credential ->
                val uidClaim = credential.payload.getClaim("uid").asString()
                if (!uidClaim.isNullOrBlank()) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                call.response<Unit>(-401, "token×", null, HttpStatusCode.Unauthorized)
            }
        }
    }
    install(Compression) {
        val contentTypes = arrayOf(
            ContentType.Text.Any,
            ContentType.Application.Json,
            ContentType.Application.JavaScript,
            ContentType.Application.Xml,
            ContentType.Application.Xml_Dtd,
            ContentType.Application.Yaml,
            ContentType.Application.FormUrlEncoded,
            ContentType.Application.Wasm,

            )
        gzip {
            matchContentType(*contentTypes)
            excludeContentType(ContentType.Text.EventStream)
        }
        deflate {
            matchContentType(*contentTypes)
            excludeContentType(ContentType.Text.EventStream)
        }

        zstd(level = 3) {
            matchContentType(*contentTypes)
            excludeContentType(ContentType.Text.EventStream)
        }
    }
    install(SSE)
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 10.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    // Log all requests in DEBUG mode
    if (DEBUG) {
        intercept(ApplicationCallPipeline.Monitoring) {
            lgr.info { "[REQ] ${call.request.httpMethod.value} ${call.request.uri}" }
        }
    }

    routing {
        playerRoutes()
        updateRoutes()
        receiptRoutes()
        yggdrasilRoutes()
        gameNodeRoutes()
        /*get("/sponsors") {
                call.respondText("""
                    2025-04-11,ChenQu,100
                    123
                    243534
                    """.trimIndent())
            }
            route("/update"){
                get("/mod-list"){
                    UpdateService.getModList(call)
                }
                get("/mod-file") {
                    UpdateService.getModFile(call)
                }
            }*/
        hostPlayRoutes()
        authenticate("auth-jwt") {
            //host2Routes()
            hostRoutes()
            worldRoutes()
            chatRoutes()
            downloadQuotaRoutes()
            modpackRoutes()
            //modpack2Routes()
            // friendRoutes() is archived.
            modFileRoutes()
            mailRoutes()
            baseWorldRoutes()
        }
    }

}
