package calebxzau.rdi.service

import calebxzhou.rdi.client.service.ModpackLocalDir
import calebxzhou.rdi.client.service.getLocalPackDir
import calebxzhou.rdi.client.service.ModpackService

/** The service only looks up the pack it starts. Import and catalog are owned by Rust. */
internal interface PackServiceDependencies : AutoCloseable {
    suspend fun findPack(versionId: String): Result<ModpackLocalDir>
    override fun close() {}
}

internal class RuntimePackServiceDependencies : PackServiceDependencies {
    override suspend fun findPack(versionId: String) = ModpackService.getLocalPackDir(versionId)
}
