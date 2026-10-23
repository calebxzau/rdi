package calebxzhou.rdi.mc.client.network

import calebxzhou.rdi.mc.client.network.GlobalPlayerListState.update
import calebxzhou.rdi.mc.common.RGlobalPlayerList
import com.google.gson.Gson
import cpw.mods.fml.common.network.ByteBufUtils
import cpw.mods.fml.common.network.simpleimpl.IMessage
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler
import cpw.mods.fml.common.network.simpleimpl.MessageContext
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft

class RGlobalPlayerListPacket : IMessage {
    private var json = ""

    override fun fromBytes(buf: ByteBuf) {
        json = ByteBufUtils.readUTF8String(buf)
        if (json.length > MAX_JSON_LENGTH) {
            json = ""
        }
    }

    override fun toBytes(buf: ByteBuf) {
        ByteBufUtils.writeUTF8String(buf, json)
    }

    class Handler : IMessageHandler<RGlobalPlayerListPacket, IMessage> {
        override fun onMessage(message: RGlobalPlayerListPacket, ctx: MessageContext): IMessage? {
            Minecraft.getMinecraft().func_152344_a {
                update(
                    GSON.fromJson(
                        message.json,
                        RGlobalPlayerList::class.java
                    )
                )
            }
            return null
        }
    }

    companion object {
        private const val MAX_JSON_LENGTH = 262144
        private val GSON = Gson()
    }
}
