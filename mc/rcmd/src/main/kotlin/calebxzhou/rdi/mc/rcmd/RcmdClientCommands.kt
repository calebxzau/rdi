package calebxzhou.rdi.mc.rcmd


object RcmdClientCommands {
    private val DISPATCHER = RcmdDispatcher()
    @JvmStatic
    fun isRcmd(message: String?): Boolean =
        message != null && message.startsWith("\\")
    @JvmStatic
    fun dispatch(bridge: RcmdClientBridge, message: String): RcmdDispatchResult {
        return DISPATCHER.dispatch(bridge, message)
    }

    @JvmStatic
    fun reply(bridge: RcmdClientBridge, result: RcmdResult?) {
        if (result == null || result.message().isEmpty()) {
            return
        }
        for (message in result.message().split("\\R".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) {
            if (message.isEmpty()) {
                continue
            }
            if (result.success()) {
                bridge.sendFeedback(message)
            } else {
                bridge.sendError(message)
            }
        }
    }
}
