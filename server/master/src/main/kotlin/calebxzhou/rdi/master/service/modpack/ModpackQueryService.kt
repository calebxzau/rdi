package calebxzhou.rdi.master.service.modpack

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.*
import calebxzhou.rdi.common.util.ok
import calebxzhou.rdi.master.exception.ParamError
import calebxzhou.rdi.master.net.*
import calebxzhou.rdi.master.service.*
import calebxzhou.rdi.master.service.PlayerService.getPlayerNames
import com.mongodb.client.model.Filters.*
import com.mongodb.client.model.Projections
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.Updates
import io.ktor.server.application.ApplicationCall
import org.bson.types.ObjectId
import org.bson.Document
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList

/** Mongo queries and DTO mapping for the active Legacy modpack catalog. */
object ModpackQueryService {
    private const val DEFAULT_SEARCH_LIMIT = 50
    private const val MAX_SEARCH_LIMIT = 60
    private const val MAX_INFO_BATCH_SIZE = 100
    private const val MAX_MISSING_BATCH_SIZE = 512
    private val dbcl get() = ModpackServiceKernel.dbcl
    internal val modernListingVersions: Set<McVersion> = McVersion.entries.filter { it.isModern }.toSet()
    private fun modernListingFilter() = `in`(Modpack::mcVer.name, modernListingVersions)

    fun Modpack.isMcVer(ver: McVersion): Boolean {
        return mcVer == ver
    }

    suspend fun ApplicationCall.modpackGuardContext(): ModpackContext {
        val requesterId = uid
        val player = PlayerService.getById(requesterId) ?: throw RequestError("用户不存在")
        val modpack = ModpackServiceKernel.dbcl.find(eq("_id", idPathParam("modpackId"))).firstOrNull()
            ?: throw RequestError("整合包不存在")
        val verName = pathParamNull("verName")?.trim()?.takeIf { it.isNotEmpty() }
        val version = verName?.let { name ->
            modpack.versions.firstOrNull { it.name == name }
        }
        return ModpackContext(player, modpack, version)
    }

    suspend fun listByAuthor(uid: ObjectId): List<Modpack> = dbcl.find(eq("authorId", uid)).toList()

    suspend fun listUploadable(player: RAccount): List<Modpack> {
        if (player.isDav) return dbcl.find().toList()
        return dbcl.find(
            or(
                eq(Modpack::authorId.name, player._id),
                eq(Modpack::allowUploaderIds.name, null),
                eq(Modpack::allowUploaderIds.name, player._id),
            )
        ).toList()
    }

    private suspend fun hasModpack(name: String): Boolean = dbcl.countDocuments(eq(Modpack::name.name, name)) > 0

    private suspend fun getModpackCount(uid: ObjectId): Int =
        dbcl.countDocuments(eq(Modpack::authorId.name, uid)).toInt()

    suspend fun getById(id: ObjectId): Modpack? = dbcl.find(eq("_id", id)).firstOrNull()

    suspend fun incrementPlayCount(modpackId: ObjectId) {
        dbcl.updateOne(
            eq(Modpack::_id.name, modpackId),
            Updates.inc(Modpack::playCount.name, 1)
        )
    }

    suspend fun searchByName(name: String): List<Modpack> {
        if (name.isBlank()) return emptyList()
        //Uses MongoDB's regex filter with case-insensitive flag ("i")
        return dbcl.find(
            and(
                modernListingFilter(),
                regex(Modpack::name.name, name, "i")
            )
        ).toList()
    }

    suspend fun search(call: ApplicationCall): Modpack.SearchResultVo {
        val keyword = call.paramNull("q")?.trim().orEmpty()
        val category = call.paramNull("category")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { catStr ->
                runCatching { Modpack.Category.valueOf(catStr) }.getOrElse {
                    throw ParamError("category无效: $catStr")
                }
            }
        val mcVer = call.paramNull("mcVer")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let {
                McVersion.from(it) ?: throw ParamError("mcVer无效")
            }
        val sort = call.paramNull("sort")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.uppercase()
            ?.let {
                runCatching { Modpack.SearchSort.valueOf(it) }.getOrElse {
                    throw ParamError("sort无效: $it")
                }
            } ?: Modpack.SearchSort.RELEVANCE
        val onlyMine = call.paramNull("mine")?.trim()?.toBooleanStrictOrNull() ?: false
        val limit = call.paramNull("limit")?.toIntOrNull()
            ?.coerceIn(1, MAX_SEARCH_LIMIT)
            ?: DEFAULT_SEARCH_LIMIT
        val offset = call.paramNull("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        val filters = buildList {
            add(modernListingFilter())
            if (onlyMine) {
                add(eq(Modpack::authorId.name, call.uid))
            }
            if (keyword.isNotBlank()) {
                add(or(
                    regex(Modpack::name.name, keyword, "i"),
                    regex(Modpack::info.name, keyword, "i")
                ))
            }
            category?.let {
                add(eq(Modpack::categories.name, it))
            }
            mcVer?.let {
                add(eq(Modpack::mcVer.name, it))
            }
        }

        val filter = when (filters.size) {
            0 -> Document()
            1 -> filters.first()
            else -> and(filters)
        }

        val sortDef = when (sort) {
            Modpack.SearchSort.RELEVANCE -> when {
                keyword.isNotBlank() -> Sorts.orderBy(
                    Sorts.descending(Modpack::playCount.name),
                    Sorts.descending("${Modpack::versions.name}.${Modpack.Version::time.name}"),
                    Sorts.ascending(Modpack::name.name)
                )

                else -> Sorts.descending("${Modpack::versions.name}.${Modpack.Version::time.name}")
            }

            Modpack.SearchSort.UPDATED -> Sorts.descending("${Modpack::versions.name}.${Modpack.Version::time.name}")
            Modpack.SearchSort.POPULAR -> Sorts.descending(Modpack::playCount.name)
            //Modpack.SearchSort.NAME -> Sorts.ascending(Modpack::name.name)
        }

        val total = dbcl.countDocuments(filter).toInt()
        val modpacks = dbcl.find(filter)
            .sort(sortDef)
            .skip(offset)
            .limit(limit)
            .toList()
        val items = toModpackVoList(modpacks)

        return Modpack.SearchResultVo(
            items = items,
            total = total,
            offset = offset,
            limit = limit,
            hasMore = offset + items.size < total
        )
    }

    suspend fun listAll(): List<Modpack.BriefVo> {
        val modpacks = dbcl.find(modernListingFilter()).toList()
        return toModpackVoList(modpacks)
    }

    suspend fun listSimple(hasIconOnly: Boolean = false): List<Modpack.ListSimpleVo> {
        val filter = if (hasIconOnly) {
            ne(Modpack::iconUrl.name, null)
        } else {
            Document()
        }
        return dbcl.find(filter)
            .sort(Sorts.descending("${Modpack::versions.name}.${Modpack.Version::time.name}"))
            .toList()
            .map { modpack ->
                Modpack.ListSimpleVo(
                    id = modpack._id,
                    name = modpack.name,
                    iconUrl = modpack.iconUrl
                )
            }
    }

    suspend fun listByIds(ids: List<ObjectId>): List<Modpack> {
        val orderedIds = normalizeInfoBatchIds(ids)
        if (orderedIds.isEmpty()) return emptyList()
        return orderModpacksByIds(orderedIds, dbcl.find(`in`("_id", orderedIds)).toList())
    }

    suspend fun findMissingIds(ids: List<ObjectId>): List<ObjectId> {
        if (ids.size > MAX_MISSING_BATCH_SIZE) {
            throw ParamError("批量检查整合包最多支持${MAX_MISSING_BATCH_SIZE}个ID")
        }
        val orderedIds = ids.distinct()
        if (orderedIds.isEmpty()) return emptyList()

        val foundIds = dbcl.distinct<ObjectId>("_id", `in`("_id", orderedIds)).toList().toSet()
        return orderedIds.filterNot(foundIds::contains)
    }

    suspend fun Modpack.getVersion(verName: String): Modpack.Version? = versions.find { it.name == verName }

    suspend fun getVersion(modpackId: ObjectId, verName: String): Modpack.Version? = dbcl.find(
        and(eq("_id", modpackId), elemMatch(Modpack::versions.name, eq(Modpack.Version::name.name, verName)))
    ).projection(Projections.elemMatch(Modpack::versions.name, eq(Modpack.Version::name.name, verName)))
        .firstOrNull()?.versions?.firstOrNull()

    internal fun normalizeInfoBatchIds(ids: List<ObjectId>): List<ObjectId> {
        if (ids.size > MAX_INFO_BATCH_SIZE) {
            throw ParamError("批量查询整合包最多支持${MAX_INFO_BATCH_SIZE}个ID")
        }
        return ids.distinct()
    }

    internal fun orderModpacksByIds(ids: List<ObjectId>, modpacks: List<Modpack>): List<Modpack> {
        val modpacksById = modpacks.associateBy { it._id }
        return ids.mapNotNull(modpacksById::get)
    }

    fun String.validateVerName(): Result<String> {
        val trimmed = this.trim()
        if (trimmed.isBlank()) {
            throw RequestError("版本名不能为空")
        }

        if (!trimmed.matches(Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+){1,3}"))) {
            throw RequestError("版本名格式必须为a.b、a.b.c或a.b.c.d，每段只能包含英文字母、数字、下划线或短横线")
        }
        return ok(trimmed)
    }

    suspend fun toModpackVoList(modpacks: List<Modpack>): List<Modpack.BriefVo> {
        if (modpacks.isEmpty()) return emptyList()

        val authorNames = modpacks.map { it.authorId }.getPlayerNames()

        return modpacks.map { pack ->
            Modpack.BriefVo(
                pack._id,
                name = pack.name,
                authorId = pack.authorId,
                authorName = authorNames[pack.authorId] ?: "未知作者",
                modCount = pack.versions.maxOfOrNull { it.mods.size } ?: 0,
                fileSize = pack.versions.lastOrNull()?.totalSize ?: 0L,
                playCount = pack.playCount,
                lastUpdatedTime = pack.versions.lastOrNull()?.time ?: 0L,
                icon = pack.iconUrl,
                mcVer = pack.mcVer,
                modloader = pack.modloader,
                info = pack.info,
                categories = pack.categories
            )
        }
    }

    suspend fun Modpack.toBriefVo(): Modpack.BriefVo {
        val authorName = PlayerService.getName(authorId) ?: "未知作者"
        return Modpack.BriefVo(
            _id,
            name = name,
            authorId = authorId,
            authorName = authorName,
            mcVer = mcVer,
            modCount = versions.maxOfOrNull { it.mods.size } ?: 0,
            fileSize = versions.lastOrNull()?.totalSize ?: 0L,
            playCount = playCount,
            lastUpdatedTime = versions.lastOrNull()?.time ?: 0L,
            icon = iconUrl,
            modloader = modloader,
            info = info,
            categories = categories
        )
    }

    //单个整合包的详细信息
    suspend fun Modpack.toDetailVo(): Modpack.DetailVo {
        val authorName = PlayerService.getName(authorId) ?: "未知作者"
        return Modpack.DetailVo(
            _id = _id,
            name = name,
            authorId = authorId,
            authorName = authorName,
            modCount = versions.maxOfOrNull { it.mods.size } ?: 0,
            playCount = playCount,
            sourceUrl = sourceUrl,
            icon = iconUrl,
            info = info,
            modloader = modloader,
            mcVer = mcVer,
            categories = categories,
            versions = versions
        )
    }

    suspend fun ModpackContext.toDetailVo(): Modpack.DetailVo =
        modpack.toDetailVo().copy(
            canUploadVersion = ModpackVersionService.run { this@toDetailVo.canUploadVersion() },
        )
}
