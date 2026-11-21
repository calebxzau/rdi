package calebxzhou.rdi.mc.client.mcp.standard

object McpStandardResources {
    const val SUMMARY_URI = "rmcp://summary"
    const val BASIC_PROMPT_NAME = "rmcp-basic"

    fun read(uri: String): String? {
        return when (uri) {
            //SUMMARY_URI -> resourceText("mcp/summary.md")
            else -> null
        }
    }

    fun basicPrompt(includeWorldActions: Boolean = true): String {
        if (!includeWorldActions) {
            return """
                Use the RDI Minecraft MCP tools to read and operate the running Minecraft client.
                Only do what the player explicitly asked.
                Call screen_read before interacting with an open screen and use the returned widget IDs and screen version.
            """.trimIndent()
        }
        return """
            Use the RDI Minecraft MCP tools to read live data from the running Minecraft client.
            Only do what the player explicitly asked.
            Prefer read-only tools before action tools.
            Before breaking a block for drops, call block_harvest_result with the target block position and the inventory slot that will be used as the tool. Only break it after the previewed drops match the player's need.
            If the player has no matching tool to harvest the target drops, you may search nearby containers for one and take it. If none is available, collect the needed materials and craft a matching tool.
            Always use block_find when finding a certain kind of block.Always use block_find when finding a certain kind of block.Always use block_find when finding a certain kind of block.
     
            Block operations can happen remotely; do not move near the block only to place, break, fetch, or inspect it.
            For destructive tools, always run a test preview first when the tool has a test parameter. Only execute the real action after the test result is acceptable.
               
        """.trimIndent()
    }

}
