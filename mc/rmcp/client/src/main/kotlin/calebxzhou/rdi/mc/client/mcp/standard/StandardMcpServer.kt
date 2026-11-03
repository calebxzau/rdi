package calebxzhou.rdi.mc.client.mcp.standard

import calebxzhou.rdi.mc.client.mcp.McpPorts
import calebxzhou.rdi.mc.client.mcp.McpGameInterface
import io.fusionauth.http.HTTPMethod
import io.fusionauth.http.server.HTTPHandler
import io.fusionauth.http.server.HTTPListenerConfiguration
import io.fusionauth.http.server.HTTPRequest
import io.fusionauth.http.server.HTTPResponse
import io.fusionauth.http.server.HTTPServer
import io.fusionauth.http.server.HTTPServerConfiguration
import java.io.File
import java.net.InetAddress
import java.util.UUID

object StandardMcpServer {
    private const val MCP_PATH = "/mcp"
    private const val MCP_SESSION_HEADER = "Mcp-Session-Id"
    private val PORT_FILE = File("standard_mcp_port.txt")

    private var game: McpGameInterface? = null
    private var server: HTTPServer? = null
    private var activePort: Int? = null
    private var sessionId: String = UUID.randomUUID().toString()
    private var shutdownHookRegistered = false

    /** The listening port, or null when the server is not running. */
    val port: Int?
        @Synchronized get() = activePort

    fun url(port: Int): String = "http://127.0.0.1:${port}${MCP_PATH}"

    @Synchronized
    fun start(game: McpGameInterface): Result<Unit> {
        return start(game, null).map { }
    }

    @Synchronized
    fun start(game: McpGameInterface, port: Int?): Result<Int> = runCatching {
        this.game = game
        var actualPort = activePort
        if (actualPort == null) {
            actualPort = port ?: McpPorts.selectPersistentPort(PORT_FILE)
            server = startServer(actualPort).getOrElse {
                actualPort = McpPorts.selectAvailablePort()
                startServer(actualPort).getOrThrow()
            }
            activePort = actualPort
            sessionId = UUID.randomUUID().toString()
            PORT_FILE.writeText(actualPort.toString())
            println("Standard MCP Server started on port $actualPort")
        }
        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(Thread(::stop, "rdi-standard-mcp-stop"))
            shutdownHookRegistered = true
        }
        actualPort!!
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        activePort = null
        sessionId = UUID.randomUUID().toString()
        game = null
    }

    private fun startServer(port: Int): Result<HTTPServer> = runCatching {
        HTTPServer()
            .withConfiguration(
                HTTPServerConfiguration()
                    .withCompressByDefault(false)
                    .withHandler(HTTPHandler { request: HTTPRequest, response: HTTPResponse ->
                        handleHttp(request, response)
                    })
                    // Loopback only: the tools act as the player, so other machines must not reach them.
                    .withListener(HTTPListenerConfiguration(InetAddress.getByName("127.0.0.1"), port))
            )
            .start()
    }

    private fun handleJsonRpc(body: String): String? {
        val request = JsonRpc.parseRequest(body).getOrElse { e ->
            val rpcError = e as? JsonRpcException
            return JsonRpc.encode(JsonRpc.error(null, rpcError?.code ?: JsonRpc.PARSE_ERROR, rpcError?.message ?: "parse error"))
        }
        val currentGame = game
            ?: return JsonRpc.encode(JsonRpc.error(request.id, JsonRpc.INTERNAL_ERROR, "game unavailable"))
        return try {
            StandardMcpProtocol.handle(request, currentGame)?.let(JsonRpc::encode)
        } catch (e: JsonRpcException) {
            JsonRpc.encode(JsonRpc.error(request.id, e.code, e.message, e.errorData))
        } catch (e: Throwable) {
            JsonRpc.encode(JsonRpc.error(request.id, JsonRpc.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun handleHttp(request: HTTPRequest, response: HTTPResponse) {
        response.setHeader(MCP_SESSION_HEADER, sessionId)
        if (!request.hasValidSession()) {
            response.writeJson(
                JsonRpc.encode(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "invalid MCP session")),
                status = 404,
            )
            return
        }
        if (request.path != MCP_PATH) {
            response.writeJson(
                JsonRpc.encode(JsonRpc.error(null, JsonRpc.METHOD_NOT_FOUND, "endpoint not found: ${request.path}")),
                status = 404,
            )
            return
        }
        if (request.method.`is`(HTTPMethod.GET)) {
            response.writeJson(
                JsonRpc.encode(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "GET $MCP_PATH is not supported")),
                status = 405,
            )
            return
        }
        if (!request.method.`is`(HTTPMethod.POST)) {
            response.writeJson(
                JsonRpc.encode(JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "POST $MCP_PATH required")),
                status = 405,
            )
            return
        }
        val body = request.bodyBytes.toString(Charsets.UTF_8)
        val responseText = handleJsonRpc(body)
        if (responseText == null) {
            response.status = 202
            response.contentType = "application/json; charset=utf-8"
            response.setHeader(MCP_SESSION_HEADER, sessionId)
            return
        }
        response.writeJson(responseText)
    }

    private fun HTTPRequest.hasValidSession(): Boolean {
        val clientSessionId = getHeader(MCP_SESSION_HEADER)?.trim().orEmpty()
        return clientSessionId.isEmpty() || clientSessionId == sessionId
    }

    private fun HTTPResponse.writeJson(text: String, status: Int = 200) {
        this.status = status
        contentType = "application/json; charset=utf-8"
        setHeader(MCP_SESSION_HEADER, sessionId)
        getWriter().write(text)
    }
}
