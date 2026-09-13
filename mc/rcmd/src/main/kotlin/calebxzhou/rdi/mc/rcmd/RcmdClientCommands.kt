package calebxzhou.rdi.mc.rcmd

import calebxzhou.rdi.mc.rcmd.RcmdCommandSpec.Companion.builder
import calebxzhou.rdi.mc.rcmd.RcmdResult.Companion.ok

object RcmdClientCommands {
    private val DISPATCHER = RcmdDispatcher()

    init {
        DISPATCHER.register(
            builder("firmsection", "display", "set")
                .description("开关已设置同步区域边框")
                .command(RcmdCommand { context: RcmdContext? -> toggleSetFirmSections(context!!.source as RcmdClientBridge) })
                .build()
        )
        DISPATCHER.register(
            builder("firmsection", "display", "now")
                .description("切换当前子区块边框")
                .command(RcmdCommand { context: RcmdContext? -> toggleNowFirmSection(context!!.source as RcmdClientBridge) })
                .build()
        )
    }
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

    private fun toggleSetFirmSections(bridge: RcmdClientBridge): RcmdResult {
        val visible = bridge.toggleSetFirmSectionsVisible()
        return ok("已设定的同步区域边框：" + (if (visible) "显示" else "隐藏"))
    }

    private fun toggleNowFirmSection(bridge: RcmdClientBridge): RcmdResult {
        val visible = bridge.toggleNowFirmSectionVisible()
        return ok("当前子区块边框：" + (if (visible) "显示" else "隐藏"))
    }
}
