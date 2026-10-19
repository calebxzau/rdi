package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.model.ModrinthProjectSearchResult
import calebxzhou.rdi.common.model.ModrinthSearchIndex

object ResourcepackSearchService {
    suspend fun searchResourcepacks(
        query: String? = null,
        mcVersion: String? = null,
        index: ModrinthSearchIndex = ModrinthSearchIndex.DOWNLOADS,
        offset: Int = 0,
        limit: Int = 20
    ): ModrinthProjectSearchResult =
        ModrinthProjectSearchService.searchProjects(
            projectType = "resourcepack",
            query = query,
            mcVersion = mcVersion,
            index = index,
            offset = offset,
            limit = limit
        )
}
