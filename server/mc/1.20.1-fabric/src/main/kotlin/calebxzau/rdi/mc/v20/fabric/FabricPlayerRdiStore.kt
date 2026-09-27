package calebxzau.rdi.mc.v20.fabric

import calebxzhou.rdi.mc.rcmd.chat.ChatRange
import calebxzhou.rdi.mc.rcmd.chat.ChatRangeStore
import calebxzhou.rdi.mc.rcmd.home.HomeLocation
import calebxzhou.rdi.mc.rcmd.home.HomePlayer
import calebxzhou.rdi.mc.rcmd.home.HomeResult
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory
import java.util.LinkedHashMap

class FabricPlayerRdiStore(private val player: ServerPlayer) : ChatRangeStore, HomePlayer {
    override fun load(): ChatRange? =
        ChatRange.fromStoredValue(data().getString(CHAT_RANGE_TAG))

    override fun save(range: ChatRange) {
        data().putString(CHAT_RANGE_TAG, range.name.lowercase(java.util.Locale.ROOT))
    }

    override fun currentLocation(): HomeLocation = HomeLocation(
        player.serverLevel().dimension().location().toString(),
        player.x,
        player.y,
        player.z,
        player.yRot,
        player.xRot
    )

    override fun loadHomes(): MutableMap<String, HomeLocation> {
        val homes = LinkedHashMap<String, HomeLocation>()
        val storedHomes = data().getCompound(HOMES_TAG)
        for (name in storedHomes.allKeys) {
            val stored = storedHomes.getCompound(name)
            val dimension = ResourceLocation.tryParse(stored.getString(DIMENSION_TAG))
            if (dimension == null || !stored.contains(X_TAG) || !stored.contains(Y_TAG) || !stored.contains(Z_TAG)) {
                logger.warn("Ignoring invalid saved home '{}' for player {}", name, player.gameProfile.name)
                continue
            }
            homes[name] = HomeLocation(
                dimension.toString(),
                stored.getDouble(X_TAG),
                stored.getDouble(Y_TAG),
                stored.getDouble(Z_TAG),
                stored.getFloat(YAW_TAG),
                stored.getFloat(PITCH_TAG)
            )
        }
        return homes
    }

    override fun saveHomes(homes: MutableMap<String, HomeLocation>) {
        val storedHomes = CompoundTag()
        homes.forEach { (name, location) ->
            val stored = CompoundTag()
            stored.putString(DIMENSION_TAG, location.dimension)
            stored.putDouble(X_TAG, location.x)
            stored.putDouble(Y_TAG, location.y)
            stored.putDouble(Z_TAG, location.z)
            stored.putFloat(YAW_TAG, location.yaw)
            stored.putFloat(PITCH_TAG, location.pitch)
            storedHomes.put(name, stored)
        }
        data().put(HOMES_TAG, storedHomes)
    }

    override fun teleportTo(location: HomeLocation): HomeResult {
        val dimension = ResourceLocation.tryParse(location.dimension)
            ?: return HomeResult.error("家的维度无效：${location.dimension}")
        val key = ResourceKey.create<Level>(Registries.DIMENSION, dimension)
        val targetLevel = player.server.getLevel(key)
            ?: return HomeResult.error("家所在的维度不存在：${location.dimension}")
        player.teleportTo(targetLevel, location.x, location.y, location.z, location.yaw, location.pitch)
        return HomeResult.ok()
    }

    private fun data(): CompoundTag =
        (player as? FabricRdiPlayerDataAccess)?.`rdi$getData`()
            ?: error("ServerPlayer RDI data Mixin is not active")

    companion object {
        private val logger = LoggerFactory.getLogger("rdi-fabric-player-data")
        private const val CHAT_RANGE_TAG = "chatRange"
        private const val HOMES_TAG = "homes"
        private const val DIMENSION_TAG = "dimension"
        private const val X_TAG = "x"
        private const val Y_TAG = "y"
        private const val Z_TAG = "z"
        private const val YAW_TAG = "yaw"
        private const val PITCH_TAG = "pitch"
    }
}
