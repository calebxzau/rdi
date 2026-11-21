package calebxzhou.rdi.mc.client.mcp.standard

import calebxzhou.rdi.mc.client.mcp.McpGameInterface
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object StandardMcpProtocol {
    const val PROTOCOL_VERSION = "2025-11-25"

    fun handle(request: JsonRpcRequest, game: McpGameInterface): JsonElement? {
        val result = when (request.method) {
            "initialize" -> initialize(game)
            "notifications/initialized" -> return null
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> toolsList(game)
            "tools/call" -> toolsCall(request.params.asObjectOrEmpty(), game)
            "resources/list" -> resourcesList()
            "resources/read" -> resourcesRead(request.params.asObjectOrEmpty())
            "resources/templates/list" -> resourceTemplatesList()
            "prompts/list" -> promptsList()
            "prompts/get" -> promptsGet(request.params.asObjectOrEmpty(), game)
            else -> throw JsonRpcException(JsonRpc.METHOD_NOT_FOUND, "method not found: ${request.method}")
        }
        return if (request.isNotification) null else JsonRpc.success(request.id, result)
    }

    private fun initialize(game: McpGameInterface): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject {
                put("listChanged", false)
            })
            put("resources", buildJsonObject {
                put("listChanged", false)
            })
            put("prompts", buildJsonObject {
                put("listChanged", false)
            })
        })
        put("serverInfo", buildJsonObject {
            put("name", "rdi-minecraft")
            put("title", "RDI Minecraft")
            put("description", "Read and act on live Minecraft client state through RDI tools.")
            put("version", game.gameVersion())
        })
        put("instructions", McpStandardResources.basicPrompt(game.supportsMcpTool("block_harvest_result")))
    }

    private fun toolsList(game: McpGameInterface): JsonObject = buildJsonObject {
        put("tools", JsonArray(StandardMcpTools.all.filter { game.supportsMcpTool(it.name) }.map { it.toProtocolJson() }))
    }

    private fun toolsCall(params: JsonObject, game: McpGameInterface): JsonObject {
        val name = params.requiredString("name")
        val arguments = params["arguments"].asObjectOrEmpty()
        val tool = StandardMcpTools.get(name)?.takeIf { game.supportsMcpTool(name) }
            ?: throw JsonRpcException(JsonRpc.INVALID_PARAMS, "unknown tool: $name")
        return tool.call(arguments, game)
            .getOrElse { StandardMcpToolResult.text(it.message ?: it.javaClass.simpleName, isError = true) }
            .toProtocolJson()
    }

    private fun resourcesList(): JsonObject = buildJsonObject {
        put("resources", buildJsonArray {
            add(buildJsonObject {
                put("uri", McpStandardResources.SUMMARY_URI)
                put("name", "RDI Minecraft MCP API summary")
                put("title", "RDI Minecraft MCP API Summary")
                put("description", "Runtime usage rules and endpoint guidance for RDI Minecraft tools.")
                put("mimeType", "text/markdown")
            })
        })
    }

    private fun resourceTemplatesList(): JsonObject = buildJsonObject {
        put("resourceTemplates", buildJsonArray {})
    }

    private fun resourcesRead(params: JsonObject): JsonObject {
        val uri = params.requiredString("uri")
        val text = McpStandardResources.read(uri)
            ?: throw JsonRpcException(JsonRpc.INVALID_PARAMS, "unknown resource: $uri")
        return buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("uri", uri)
                    put("mimeType", "text/markdown")
                    put("text", text)
                })
            })
        }
    }

    private fun promptsList(): JsonObject = buildJsonObject {
        put("prompts", buildJsonArray {
            add(buildJsonObject {
                put("name", McpStandardResources.BASIC_PROMPT_NAME)
                put("title", "RDI Minecraft Basic Rules")
                put("description", "Rules for safely using the RDI Minecraft MCP tools.")
            })
        })
    }

    private fun promptsGet(params: JsonObject, game: McpGameInterface): JsonObject {
        val name = params.requiredString("name")
        if (name != McpStandardResources.BASIC_PROMPT_NAME) {
            throw JsonRpcException(JsonRpc.INVALID_PARAMS, "unknown prompt: $name")
        }
        return buildJsonObject {
            put("title", "RDI Minecraft Basic Rules")
            put("description", "Rules for safely using the RDI Minecraft MCP tools.")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonObject {
                        put("type", "text")
                        put("text", McpStandardResources.basicPrompt(game.supportsMcpTool("block_harvest_result")))
                    })
                })
            })
        }
    }
}
