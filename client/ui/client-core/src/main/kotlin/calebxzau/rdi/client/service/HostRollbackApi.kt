package calebxzau.rdi.client.service

import calebxzau.rdi.common.model.HostRollbackCreateVo
import calebxzau.rdi.common.model.HostRollbackStatusVo
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.common.exception.RequestError
import io.ktor.http.HttpMethod

/** Server calls of the host rollback (回档), `/host/{hostId}/rollback`; owner only. */
interface HostRollbackApi {
    suspend fun status(hostId: String): HostRollbackStatusVo

    /** Leaves a rollback to [pointId] for the next start, stopping a running host first. */
    suspend fun request(hostId: String, pointId: String): HostRollbackCreateVo

    suspend fun cancel(hostId: String)
}

object RdiHostRollbackApi : HostRollbackApi {
    override suspend fun status(hostId: String): HostRollbackStatusVo {
        val response = server.makeRequest<HostRollbackStatusVo>("host/${hostId}/rollback")
        if (!response.ok) throw RequestError(response.msg, errorCode = response.errorCode)
        return response.data ?: throw RequestError("服务器响应缺少回档信息")
    }

    override suspend fun request(hostId: String, pointId: String): HostRollbackCreateVo {
        val response = server.makeRequest<HostRollbackCreateVo>("host/${hostId}/rollback", HttpMethod.Post, mapOf("pointId" to pointId))
        if (!response.ok) throw RequestError(response.msg, errorCode = response.errorCode)
        return response.data ?: throw RequestError("服务器响应缺少回档结果")
    }

    override suspend fun cancel(hostId: String) {
        val response = server.makeRequest<Unit>("host/${hostId}/rollback", HttpMethod.Delete)
        if (!response.ok) throw RequestError(response.msg, errorCode = response.errorCode)
    }
}
