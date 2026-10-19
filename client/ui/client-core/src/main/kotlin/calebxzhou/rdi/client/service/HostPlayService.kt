package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.Host

suspend fun startHostPlay(hostId: String): Result<StartPlayResult> = runCatching {
    val response = server.makeRequest<Host.DetailVo>("host/$hostId/detail")
    if (!response.ok) {
        throw RequestError(response.msg)
    }
    val detail = response.data ?: throw RequestError("无法加载房间信息")
    detail.startPlay()
}
