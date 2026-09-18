package calebxzau.rdi.mc.client.dm

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import org.slf4j.LoggerFactory

object DmUi {
    private val logger = LoggerFactory.getLogger(DmUi::class.java)

    @JvmStatic
    fun isEnabled(): Boolean = DmConfig.fromSystemProperty().fold(
        onSuccess = { it != null },
        onFailure = {
            logger.error("Invalid rdi.dm.server; DM UI disabled", it)
            false
        },
    )

    @JvmStatic
    fun createJoinButton(screenWidth: Int): Button = Button.builder(
        net.minecraft.network.chat.Component.literal("加入DM房间"),
    ) {
        val current = Minecraft.getInstance().screen
        if (current != null) Minecraft.getInstance().setScreen(DmJoinScreen(current))
    }.bounds(screenWidth / 2 - 100, 22, 200, 20).build()

    @JvmStatic
    fun layoutJoinButton(button: Button, screenWidth: Int) {
        button.setX(screenWidth / 2 - 100)
        button.setY(22)
        button.setWidth(200)
        button.setHeight(20)
    }
}
