package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.model.ModrinthProjectSearchResult
import calebxzhou.rdi.common.model.ModrinthSearchIndex
import calebxzhou.rdi.common.service.ModrinthService

object ModrinthProjectSearchService {
    suspend fun searchProjects(
        projectType: String,
        query: String? = null,
        mcVersion: String? = null,
        loader: String? = null,
        index: ModrinthSearchIndex = ModrinthSearchIndex.DOWNLOADS,
        offset: Int = 0,
        limit: Int = 20
    ): ModrinthProjectSearchResult {
        val facets = buildList {
            add(listOf("project_type:$projectType"))
            mcVersion?.trim()?.takeIf(String::isNotBlank)?.let { add(listOf("versions:$it")) }
            loader?.trim()?.takeIf(String::isNotBlank)?.let { add(listOf("categories:$it")) }
        }
        val response = ModrinthService.searchProjects(
            query = query,
            facets = facets,
            index = index,
            offset = offset,
            limit = limit
        )
        return ModrinthProjectSearchResult(
            projects = response.hits
                .filter { it.projectType == projectType }
                .map { it.toModrinthProjectCardVo() },
            offset = response.offset,
            limit = response.limit,
            totalHits = response.totalHits
        )
    }
}
