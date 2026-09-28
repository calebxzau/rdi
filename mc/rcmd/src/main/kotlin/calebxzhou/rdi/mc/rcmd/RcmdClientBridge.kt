package calebxzhou.rdi.mc.rcmd

import calebxzhou.rdi.mc.rcmd.RcmdSource
import java.nio.file.Path

interface RcmdClientBridge : RcmdSource {
    fun gameDirectory(): Path

    fun executeOnMainThread(task: Runnable)
}
