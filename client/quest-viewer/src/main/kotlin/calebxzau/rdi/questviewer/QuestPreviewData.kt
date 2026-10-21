package calebxzau.rdi.questviewer

import calebxzau.rdi.quests.ftb.FtbQuestBookReader
import calebxzau.rdi.quests.ftb.FtbQuestLocalizer
import calebxzau.rdi.quests.ftb.model.*
import calebxzau.rdi.quests.ftb.snbt.FtbSnbtData
import kotlinx.serialization.json.*
import net.benwoodworth.knbt.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CancellationException

/** [chapterId] is null for the whole book, and for a chapter group without chapters. */
internal data class PreviewTarget(val id: String, val chapterId: String?, val questId: String?, val label: String)

internal data class ChapterSection(val group: FtbChapterGroup?, val groupId: String?, val chapters: List<FtbChapter>)

internal class QuestPreviewData(
    val book: FtbQuestBook,
    val assets: PreviewAssets,
    val issues: List<String>,
) {
    val chapters = book.chapters.associateBy { it.id }
    val groups = book.chapterGroups.associateBy { it.id }
    val quests = book.chapters.flatMap { it.quests }.associateBy { it.id }
    val tables = book.rewardTables.mapNotNull { table -> table.id?.let { it to table } }.toMap()
    /** Each group appears once even if its chapters are not contiguous in the sorted book. */
    val chapterSections = book.chapters.groupBy { it.groupId }.map { (groupId, entries) ->
        ChapterSection(groups[groupId], groupId, entries)
    }
    private val targets = linkedMapOf<String, PreviewTarget>()
    private val tags = linkedMapOf<String, MutableSet<String>>()

    init {
        fun register(id: String, chapterId: String?, quest: FtbQuest?, title: String, data: FtbSnbtData) {
            targets[id] = PreviewTarget(id, chapterId, quest?.id, title)
            (data.nbt["tags"] as? NbtList<*>)?.forEach { tag ->
                (tag as? NbtString)?.value?.let { tags.getOrPut(it) { linkedSetOf() }.add(id) }
            }
        }
        fun register(id: String, chapter: FtbChapter, quest: FtbQuest?, title: String, data: FtbSnbtData) =
            register(id, chapter.id, quest, title, data)
        // FTB reserves this ID for the book; quests may depend on it or on a chapter group.
        register(BOOK_ID, null, null, book.title?.takeIf(String::isNotBlank)?.let(::text) ?: "整本任务书", book.settings)
        book.chapterGroups.forEach { group ->
            val title = group.title?.takeIf(String::isNotBlank)?.let(::text) ?: "章节组${group.id}"
            register(group.id, book.chapters.firstOrNull { it.groupId == group.id }?.id, null, title, group.data)
        }
        book.chapters.forEach { chapter ->
            register(chapter.id, chapter, null, chapterTitle(chapter), chapter.data)
            chapter.quests.forEach { quest ->
                register(quest.id, chapter, quest, questTitle(quest), quest.data)
                quest.tasks.forEach { task -> register(task.id, chapter, quest, taskTitle(task), task.data) }
                quest.rewards.forEach { reward ->
                    reward.id?.let { register(it, chapter, quest, rewardTitle(reward), reward.data) }
                }
            }
            chapter.links.forEach { link ->
                val quest = quests[link.linkedQuestId]
                val targetChapter = book.chapters.firstOrNull { c -> c.quests.any { it.id == quest?.id } }
                if (quest != null && targetChapter != null) {
                    register(link.id, targetChapter, quest, questTitle(quest), link.data)
                }
            }
        }
    }

    // Built once off the UI thread by the loader; lowercase so each keystroke is a plain scan.
    private val searchIndex = quests.values.map { quest ->
        quest to searchableQuestText(listOf(
            quest.id, questTitle(quest), quest.subtitle?.let(::text).orEmpty(),
            quest.description.joinToString("\n") { text(it) },
            quest.tasks.joinToString(" ") { taskTitle(it) + " " + it.item?.itemId.orEmpty() },
            quest.rewards.joinToString(" ") { rewardTitle(it) + " " + it.item?.itemId.orEmpty() },
        ).joinToString("\n")).lowercase(Locale.ROOT)
    }

    fun search(query: String): List<FtbQuest> {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return emptyList()
        return searchIndex.mapNotNull { (quest, content) -> quest.takeIf { needle in content } }
    }

    fun resolve(reference: String): List<PreviewTarget> = if (reference.startsWith('#')) {
        tags[reference.drop(1)].orEmpty().mapNotNull(targets::get)
    } else listOfNotNull(targets[reference])

    fun text(raw: String): String = previewPlainText(raw, assets.language)
    fun chapterTitle(chapter: FtbChapter): String = chapter.title?.takeIf(String::isNotBlank)?.let(::text)
        ?: chapter.sourcePath.substringAfterLast('/').removeSuffix(".snbt")
    fun questIcon(quest: FtbQuest): PreviewIcon? = quest.icon?.let { PreviewIcon.Item(it.itemId) }
        ?: quest.tasks.firstNotNullOfOrNull { it.previewIcon() }
    fun targetIcon(target: PreviewTarget): PreviewIcon? = target.questId?.let(quests::get)?.let(::questIcon)
        ?: (chapters[target.id]?.icon ?: groups[target.id]?.icon)?.let { PreviewIcon.Item(it.itemId) }
    fun questTitle(quest: FtbQuest): String = quest.title?.takeIf(String::isNotBlank)?.let(::text)
        ?: quest.tasks.firstOrNull()?.let(::taskTitle) ?: "任务${quest.id}"
    fun repeatable(quest: FtbQuest): Boolean = quest.repeatable
        ?: resolve(quest.id).singleOrNull()?.chapterId?.let(chapters::get)?.data?.flag("default_repeatable_quest") ?: false
    fun taskTitle(task: FtbTask): String = task.title?.takeIf(String::isNotBlank)?.let(::text)
        ?: task.item?.let { assets.itemName(it.itemId) } ?: typeTitle("task", task.type)
    fun rewardTitle(reward: FtbReward): String = reward.title?.takeIf(String::isNotBlank)?.let(::text)
        ?: reward.item?.let { assets.itemName(it.itemId) } ?: typeTitle("reward", reward.type)

    private fun typeTitle(kind: String, type: String): String {
        val qualified = if (':' in type) type else "ftbquests:$type"
        return assets.language["ftbquests.${kind}.${qualified.replace(':', '.')}"] ?: when (qualified) {
            "ftbquests:item" -> "物品"
            "ftbquests:checkmark" -> "确认完成"
            "ftbquests:xp" -> "经验"
            "ftbquests:xp_levels" -> "经验等级"
            "ftbquests:command" -> "命令奖励"
            "ftbquests:kill" -> "击败生物"
            "ftbquests:location" -> "到达地点"
            "ftbquests:advancement" -> "达成进度"
            "ftbquests:random" -> "随机奖励"
            "ftbquests:choice" -> "自选奖励"
            else -> type
        }
    }
}

private const val BOOK_ID = "0000000000000001"

internal object QuestPreviewLoader {
    fun load(modpackDir: Path): Result<QuestPreviewData> = try {
        require(Files.isDirectory(modpackDir)) { "整合包目录不存在：$modpackDir" }
        val questsDir = modpackDir.resolve("config/ftbquests/quests")
        require(Files.isDirectory(questsDir)) { "此整合包没有FTB任务书（config/ftbquests/quests）。" }
        val result = FtbQuestBookReader.read(questsDir).getOrThrow()
        val issues = result.diagnostics.map { diagnostic ->
            "${diagnostic.path ?: diagnostic.objectId ?: "任务书"}：${diagnostic.message}"
        }.toMutableList()
        val assets = PreviewAssetReader.read(modpackDir.resolve("rdi/preview"), issues)
        val shapes = buildSet {
            result.book.settings.string("default_quest_shape")?.let(::add)
            result.book.chapters.forEach { chapter ->
                chapter.data.string("default_quest_shape")?.let(::add)
                chapter.quests.mapNotNullTo(this) { it.shape }
                chapter.links.mapNotNullTo(this) { it.shape }
            }
        }.filterNot { it.isBlank() || it in setOf("default", "circle", "square", "diamond") }
        if (shapes.isNotEmpty()) issues += "部分任务形状将以圆形显示：${shapes.sorted().joinToString("、")}"
        Result.success(QuestPreviewData(FtbQuestLocalizer.localize(result.book, "zh_cn"), assets, issues))
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }
}

internal fun FtbSnbtData.string(key: String): String? = (nbt[key] as? NbtString)?.value
internal fun FtbSnbtData.number(key: String): Double? = when (val tag = nbt[key]) {
    is NbtDouble -> tag.value
    is NbtFloat -> tag.value.toDouble()
    is NbtLong -> tag.value.toDouble()
    is NbtInt -> tag.value.toDouble()
    is NbtShort -> tag.value.toDouble()
    is NbtByte -> tag.value.toDouble()
    else -> null
}
internal fun FtbSnbtData.flag(key: String): Boolean? = number(key)?.let { it != 0.0 }

/** Plain representation for search; unsupported components remain visible as source text. */
internal fun previewPlainText(raw: String, language: Map<String, String>): String {
    fun component(value: JsonElement, depth: Int): String {
        if (depth > 32) return value.toString()
        return when (value) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.joinToString("") { component(it, depth + 1) }
            is JsonObject -> {
                val literal = (value["text"] as? JsonPrimitive)?.content
                val key = (value["translate"] as? JsonPrimitive)?.content
                val base = literal ?: key?.let { language[it] ?: it } ?: return value.toString()
                val args = (value["with"] as? JsonArray).orEmpty().map { component(it, depth + 1) }
                var sequential = 0
                val formatted = Regex("%(?:(\\d+)\\$)?s|%%").replace(base) { match ->
                    if (match.value == "%%") "%" else {
                        val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: sequential++
                        args.getOrNull(index) ?: match.value
                    }
                }
                formatted + (value["extra"] as? JsonArray).orEmpty().joinToString("") { component(it, depth + 1) }
            }
        }
    }
    val translated = language[raw] ?: raw
    if (!translated.trimStart().startsWith('{') && !translated.trimStart().startsWith('[')) return translated
    return try {
        component(Json.parseToJsonElement(translated), 0)
    } catch (_: IllegalArgumentException) {
        // This is ordinary quest prose, not necessarily a JSON component.
        translated
    }
}
