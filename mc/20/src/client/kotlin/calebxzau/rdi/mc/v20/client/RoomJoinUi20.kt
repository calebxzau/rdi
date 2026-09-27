package calebxzau.rdi.mc.v20.client

import calebxzhou.rdi.mc.common.RDI
import com.google.common.net.HostAndPort
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/** Shared 1.20.1 room join button and its title-screen layout. */
object RoomJoinUi20 {
    private val logger = LoggerFactory.getLogger("RDI Room Join")

    @JvmField
    val JOIN_BUTTON: Button = createRoomButton(TitleScreen()).size(500, 50).build()

    @JvmStatic
    fun createRoomButton(parent: Screen): Button.Builder =
        Button.builder(Component.literal("进入房间 · ${RDI.HOST_NAME}")) {
            connectToRoom(parent)
        }

    @JvmStatic
    fun connectToRoom(parent: Screen) {
        val minecraft = Minecraft.getInstance()
        val serverAddress = try {
            require(ServerAddress.isValidAddress(RDI.GAME_IP)) { "RDI房间地址中的主机名无效" }
            val hostAndPort = HostAndPort.fromString(RDI.GAME_IP)
            require(hostAndPort.hasPort()) { "RDI房间地址缺少端口" }
            require(hostAndPort.port in 1..65535) { "RDI房间地址中的端口无效" }
            ServerAddress(hostAndPort.host, hostAndPort.port)
        } catch (error: RuntimeException) {
            logger.error("RDI房间连接信息无效：{}", RDI.GAME_IP, error)
            minecraft.setScreen(
                DisconnectedScreen(
                    parent,
                    Component.literal("无法进入房间"),
                    Component.literal("房间连接信息无效，请从RDI重新启动游戏")
                )
            )
            return
        }

        ConnectScreen.startConnecting(
            parent,
            minecraft,
            serverAddress,
            ServerData(RDI.HOST_NAME, RDI.GAME_IP, false),
            false
        )
    }

    @JvmStatic
    fun layoutJoinButton(screenWidth: Int) {
        JOIN_BUTTON.x = screenWidth / 2 - 250
        JOIN_BUTTON.y = 0
        JOIN_BUTTON.width = 500
    }
}
