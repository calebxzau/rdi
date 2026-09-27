package calebxzau.rdi.mc.v20.client

import com.mojang.authlib.GameProfile
import com.mojang.authlib.minecraft.MinecraftProfileTexture
import calebxzau.rdi.mc.v20.client.mixin.ASkinManager
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.resources.SkinManager
import net.minecraft.resources.ResourceLocation
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object GlobalPlayerAvatarCache {
    private val logger = LoggerFactory.getLogger("RDI Tab Avatars")
    private val skins = ConcurrentHashMap<UUID, ResourceLocation>()
    private val loading = ConcurrentHashMap<UUID, Any>()
    private val retryAfter = ConcurrentHashMap<UUID, Long>()

    @JvmStatic
    fun skin(playerId: UUID, playerName: String?): ResourceLocation {
        skins[playerId]?.let { return it }
        val session = GlobalPlayerListState.session() ?: return DefaultPlayerSkin.getDefaultSkin(playerId)
        if (retryAfter.getOrDefault(playerId, 0L) > System.currentTimeMillis()) {
            return DefaultPlayerSkin.getDefaultSkin(playerId)
        }
        retryAfter[playerId] = System.currentTimeMillis() + RETRY_COOLDOWN_MILLIS
        val attempt = Any()
        if (loading.putIfAbsent(playerId, attempt) != null) return DefaultPlayerSkin.getDefaultSkin(playerId)
        RdiClothesResolver.resolve(session, playerId).whenComplete { result, throwable ->
            if (throwable != null) {
                logger.error("Failed to resolve RDI skin for {}", playerId, throwable)
                loading.remove(playerId, attempt)
                return@whenComplete
            }
            result.fold(
                onSuccess = { clothes ->
                    Minecraft.getInstance().execute {
                        if (loading[playerId] !== attempt ||
                            !GlobalPlayerListState.isCurrent(session) || !GlobalPlayerListState.containsPlayer(playerId)
                        ) {
                            loading.remove(playerId, attempt)
                            return@execute
                        }
                        try {
                            val manager = Minecraft.getInstance().skinManager as ASkinManager
                            val location = manager.`rdi$registerTexture`(
                                clothes.skin,
                                MinecraftProfileTexture.Type.SKIN,
                                SkinManager.SkinTextureCallback { type, location, _ ->
                                    if (type == MinecraftProfileTexture.Type.SKIN &&
                                        loading[playerId] === attempt &&
                                        GlobalPlayerListState.isCurrent(session) && GlobalPlayerListState.containsPlayer(playerId)
                                    ) {
                                        skins[playerId] = location
                                        retryAfter.remove(playerId)
                                    }
                                }
                            )
                            if (loading[playerId] === attempt &&
                                GlobalPlayerListState.isCurrent(session) && GlobalPlayerListState.containsPlayer(playerId)
                            ) {
                                skins[playerId] = location
                                retryAfter.remove(playerId)
                            }
                        } catch (error: Exception) {
                            logger.error("Failed to register RDI skin texture for {}", playerId, error)
                        } finally {
                            loading.remove(playerId, attempt)
                        }
                    }
                },
                onFailure = { error ->
                    logger.error("Failed to resolve RDI skin for {}", playerId, error)
                    loading.remove(playerId, attempt)
                }
            )
        }
        return DefaultPlayerSkin.getDefaultSkin(playerId)
    }

    @JvmStatic
    fun retainPlayers(playerList: calebxzhou.rdi.mc.common.RGlobalPlayerList) {
        val retained = playerList.hosts().flatMap { host -> host.players().map { playerIdToUuid(it.playerId()) } }.toSet()
        skins.keys.retainAll(retained)
        loading.keys.retainAll(retained)
        retryAfter.keys.retainAll(retained)
    }

    @JvmStatic
    fun resetSession() {
        skins.clear()
        loading.clear()
        retryAfter.clear()
    }

    private const val RETRY_COOLDOWN_MILLIS = 30_000L
}
