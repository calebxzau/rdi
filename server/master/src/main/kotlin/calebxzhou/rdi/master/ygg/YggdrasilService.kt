package calebxzhou.rdi.master.ygg

import calebxzhou.rdi.common.util.encodeBase64
import calebxzhou.rdi.common.UNKNOWN_PLAYER_ID
import calebxzhou.rdi.common.json
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.util.objectId
import calebxzhou.rdi.common.util.toUUID
import calebxzhou.rdi.master.net.param
import calebxzhou.rdi.master.net.paramNull
import calebxzhou.rdi.master.service.PlayerService
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import java.util.*

object YggdrasilService {
    fun Route.yggdrasilRoutes() {
        post("/minecraft/profile/lookup/bulk/byname"){
            call.getProfiles(call.receive())
        }
        get("/session/minecraft/profile/{uuid}"){
            call.getProfile(param("uuid"))
        }
        //http://127.0.0.1:65231/mc-profile/380df991-f603-344c-a090-369bad2a924a/clothes
        get("/mc-profile/{uuid}/clothes"){
                call.getClothes(param("uuid"),paramNull("authlibVer"))

        }
    }

    val String.fromUndashedUuid: UUID
        get() = UUID.fromString(this.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})".toRegex(), "$1-$2-$3-$4-$5"))
    val UUID.undashedString: String
        get() = this.toString().replace("-","")
    val RAccount.gameProfile: GameProfile
        get() {
            val texturesPayload = MinecraftTexturesPayload(
                timestamp = System.currentTimeMillis(),
                profileId = _id.toUUID().undashedString,
                profileName = name,
                true,
                textures = mutableMapOf(
                    MinecraftProfileTexture.Type.SKIN to MinecraftProfileTexture(
                        cloth.skin,
                        metadata = if (cloth.isSlim) mapOf("model" to "slim" ) else mapOf()
                    )
                ).apply {
                    cloth.cape?.let {
                        this += MinecraftProfileTexture.Type.CAPE to MinecraftProfileTexture(it)
                    }
                }
            )
            return GameProfile(
                _id.toUUID().undashedString,
                this.name,
                listOf(Property(name = "textures", value = texturesPayload.json.encodeBase64))
            )
        }
    private suspend fun ApplicationCall.getProfile(uuid: String){
        val uid = uuid.fromUndashedUuid
        val account = runCatching {  PlayerService.getById(uid.objectId) }.getOrNull()
        respond(account?.gameProfile?: GameProfile.getDefault(uuid))
    }
    private suspend fun ApplicationCall.getClothes(uuid: String, authlibVer: String?){
        val account = PlayerService.getById(UUID.fromString(uuid).objectId)?: RAccount.DEFAULT
        when(authlibVer){
            //authlib 4 for 1.20
            "4" -> {
                val clothMap: Map<MinecraftProfileTexture.Type, MinecraftProfileTexture> = mutableMapOf(
                    MinecraftProfileTexture.Type.SKIN to MinecraftProfileTexture(
                        account.cloth.skin,
                        metadata = if (account.cloth.isSlim) mapOf("model" to "slim" ) else mapOf()
                    )
                ).apply {
                    account.cloth.cape?.let {
                        this += MinecraftProfileTexture.Type.CAPE to MinecraftProfileTexture(it)
                    }
                }
                respond(clothMap)
            }
            //默认最新版 authlib 6 for 1.21+
            else -> {
                val clothes = MinecraftProfileTextures(
                    MinecraftProfileTexture(account.cloth.skin, if (account.cloth.isSlim) mapOf("model" to "slim" ) else mapOf()),
                    account.cloth.cape?.let { MinecraftProfileTexture(it) }
                )
                respond(clothes)
            }
        }
    }
    private suspend fun ApplicationCall.getProfiles(names: List<String>){
        val profiles = names.map { name ->
             PlayerService.getByName(name) ?: RAccount(UNKNOWN_PLAYER_ID, name,"","")
        }.map { it.gameProfile }
        respond(profiles)
    }

}
