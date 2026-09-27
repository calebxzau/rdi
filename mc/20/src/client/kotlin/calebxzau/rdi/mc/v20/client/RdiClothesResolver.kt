package calebxzau.rdi.mc.v20.client

import calebxzhou.rdi.mc.common.RDI
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.mojang.authlib.HttpAuthenticationService
import com.mojang.authlib.minecraft.MinecraftProfileTexture
import net.minecraft.Util
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import calebxzau.rdi.mc.v20.client.mixin.AMinecraft
import java.util.UUID
import java.util.concurrent.CompletableFuture

object RdiClothesResolver {
    class Clothes internal constructor(
        val skin: MinecraftProfileTexture,
        val cape: MinecraftProfileTexture?
    )

    private val logger = LoggerFactory.getLogger("RDI Clothes")
    private val gson = Gson()
    private val responseType = object : TypeToken<Map<MinecraftProfileTexture.Type, MinecraftProfileTexture>>() {}.type
    private val lock = Any()
    private val successful = HashMap<UUID, Clothes>()
    private val inFlight = HashMap<UUID, CompletableFuture<Result<Clothes>>>()

    @JvmStatic
    fun resolve(session: GlobalPlayerListState.Session, uuid: UUID): CompletableFuture<Result<Clothes>> {
        if (!GlobalPlayerListState.isCurrent(session)) {
            return CompletableFuture.completedFuture(Result.failure(IllegalStateException("RDI player session expired")))
        }
        synchronized(lock) {
            successful[uuid]?.let { return CompletableFuture.completedFuture(Result.success(it)) }
            inFlight[uuid]?.let { return it }
            val future = CompletableFuture<Result<Clothes>>()
            inFlight[uuid] = future
            Util.backgroundExecutor().execute {
                val result = runCatching {
                    val auth = (Minecraft.getInstance() as AMinecraft).`rdi$getAuthenticationService`()
                    val url = HttpAuthenticationService.constantURL(RDI.getTextureQueryUrl(uuid, "4"))
                    val body = auth.performGetRequest(url)
                    val textures = requireNotNull(gson.fromJson<Map<MinecraftProfileTexture.Type, MinecraftProfileTexture>>(body, responseType)) {
                        "RDI clothes response was null"
                    }
                    val skin = requireNotNull(textures[MinecraftProfileTexture.Type.SKIN]) {
                        "RDI clothes response did not contain a skin"
                    }
                    validateTexture(skin, "skin")
                    val cape = textures[MinecraftProfileTexture.Type.CAPE]?.also { validateTexture(it, "cape") }
                    Clothes(skin, cape)
                }
                val publish = synchronized(lock) {
                    val wasPending = inFlight.remove(uuid, future)
                    if (wasPending && result.isSuccess && GlobalPlayerListState.isCurrent(session)) {
                        successful[uuid] = result.getOrThrow()
                        true
                    } else false
                }
                future.complete(
                    if (!GlobalPlayerListState.isCurrent(session)) {
                        Result.failure(IllegalStateException("RDI player session expired"))
                    } else result
                )
                if (!publish && result.isSuccess) {
                    logger.debug("Discarded clothes response for expired player session {}", uuid)
                }
            }
            return future
        }
    }

    @JvmStatic
    fun resolveClothes(session: GlobalPlayerListState.Session, uuid: UUID): CompletableFuture<Clothes> {
        val future = CompletableFuture<Clothes>()
        resolve(session, uuid).whenComplete { result, throwable ->
            if (throwable != null) {
                future.completeExceptionally(throwable)
            } else {
                result.fold(
                    onSuccess = future::complete,
                    onFailure = future::completeExceptionally
                )
            }
        }
        return future
    }

    @JvmStatic
    fun resetSession() {
        synchronized(lock) {
            successful.clear()
            inFlight.values.forEach { it.complete(Result.failure(IllegalStateException("RDI player session expired"))) }
            inFlight.clear()
        }
    }

    private fun validateTexture(texture: MinecraftProfileTexture, label: String) {
        require(texture.url.isNotBlank()) { "RDI $label texture has no URL" }
        require(texture.hash.isNotBlank()) { "RDI $label texture has no hash" }
    }
}
