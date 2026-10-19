package calebxzhou.rdi.client.service

import calebxzau.rdi.client.blessingskin.ResolvedBlessingTexture
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.RAccount
import calebxzhou.rdi.common.service.MojangApi
import calebxzhou.rdi.common.service.MojangApi.textures

object SkinService {
    suspend fun applyBlessingTexture(
        current: RAccount.Cloth,
        texture: ResolvedBlessingTexture
    ): Result<RAccount.Cloth> = runCatching {
        val newCloth = current.copy()
        if (texture.type.isCape) {
            newCloth.cape = texture.textureUrl
        } else {
            newCloth.isSlim = texture.type.isSlim
            newCloth.skin = texture.textureUrl
        }
        PlayerService.setCloth(newCloth).getOrThrow()
        newCloth
    }

    suspend fun importMojangSkin(
        name: String,
        importSkin: Boolean,
        importCape: Boolean
    ): Result<RAccount.Cloth> = runCatching {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            throw RequestError("请输入玩家名")
        }
        if (!importSkin && !importCape) {
            throw RequestError("请选择皮肤或披风")
        }
        val uuid = MojangApi.getUuidFromName(trimmedName).getOrThrow() ?: run {
            throw RequestError("玩家${trimmedName}不存在")
        }
        val textures = MojangApi.getProfile(uuid).getOrThrow().textures
        val skin = textures["SKIN"] ?: run {
            throw RequestError("玩家${trimmedName}没有设置过皮肤")
        }
        val cape = textures["CAPE"]

        val cloth = RAccount.Cloth(
            isSlim = skin.metadata?.model.equals("slim", ignoreCase = true),
            skin = skin.url
        )
        if (importCape) {
            cape?.let { cloth.cape = it.url }
        }
        PlayerService.setCloth(cloth).getOrThrow()
        cloth
    }
}
