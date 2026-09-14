package calebxzau.rdi.quests.ftb.model

import calebxzau.rdi.quests.ftb.snbt.FtbSnbtData
import kotlinx.serialization.Serializable

/** Version and producer information for an immutable quest definition snapshot. */
@Serializable
data class FtbQuestSource(
    val mcVersion: String? = null,
    val ftbQuestsVersion: String? = null,
)

@Serializable
data class FtbQuestBook(
    val source: FtbQuestSource = FtbQuestSource(),
    val dataVersion: Int,
    val settings: FtbSnbtData = FtbSnbtData(),
    val chapterGroups: List<FtbChapterGroup> = emptyList(),
    val chapters: List<FtbChapter> = emptyList(),
    val rewardTables: List<FtbRewardTable> = emptyList(),
    val languages: Map<String, FtbSnbtData> = emptyMap(),
    val chapterGroupsData: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbChapterGroup(
    /** Canonical uppercase, zero-padded hexadecimal form of the source's 64-bit ID. */
    val id: String,
    val title: String? = null,
    val subtitle: String? = null,
    val icon: FtbItemStack? = null,
    val tags: List<String> = emptyList(),
    val orderIndex: Int? = null,
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbChapter(
    val id: String,
    /** Relative path of the source SNBT file under the quests directory. */
    val sourcePath: String,
    /** Null represents the implicit default group (missing, empty, or zero source ID). */
    val groupId: String? = null,
    val orderIndex: Int? = null,
    val title: String? = null,
    val subtitle: List<String> = emptyList(),
    val icon: FtbItemStack? = null,
    val tags: List<String> = emptyList(),
    val quests: List<FtbQuest> = emptyList(),
    val links: List<FtbQuestLink> = emptyList(),
    val images: List<FtbChapterImage> = emptyList(),
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbQuest(
    val id: String,
    val title: String? = null,
    val subtitle: String? = null,
    val description: List<String> = emptyList(),
    val icon: FtbItemStack? = null,
    val tags: List<String> = emptyList(),
    val x: Double = 0.0,
    val y: Double = 0.0,
    val shape: String? = null,
    /** Null means that the chapter/default size is inherited. */
    val size: Double? = null,
    val dependencies: List<String> = emptyList(),
    val dependencyRequirement: String? = null,
    val minRequiredDependencies: Int? = null,
    val optional: Boolean = false,
    /** Null preserves an omitted tri-state value so callers can apply FTB defaults. */
    val repeatable: Boolean? = null,
    val tasks: List<FtbTask> = emptyList(),
    val rewards: List<FtbReward> = emptyList(),
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbTask(
    val id: String,
    val type: String = "item",
    val title: String? = null,
    val icon: FtbItemStack? = null,
    val tags: List<String> = emptyList(),
    val optional: Boolean = false,
    val item: FtbItemStack? = null,
    val count: Long? = null,
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbReward(
    val id: String? = null,
    val type: String = "item",
    val title: String? = null,
    val icon: FtbItemStack? = null,
    val tags: List<String> = emptyList(),
    val item: FtbItemStack? = null,
    val count: Long? = null,
    val tableId: String? = null,
    val inlineTable: FtbRewardTable? = null,
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbRewardTable(
    val id: String? = null,
    val sourcePath: String? = null,
    val orderIndex: Int? = null,
    val title: String? = null,
    val icon: FtbItemStack? = null,
    val tags: List<String> = emptyList(),
    val entries: List<FtbWeightedReward> = emptyList(),
    val emptyWeight: Float = 0f,
    /** Null preserves the distinction between an omitted loot_size and zero. */
    val lootSize: Int? = null,
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbWeightedReward(
    val reward: FtbReward,
    val weight: Float = 1f,
)

@Serializable
data class FtbQuestLink(
    val id: String,
    val linkedQuestId: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val shape: String? = null,
    val size: Double? = null,
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbChapterImage(
    val id: String? = null,
    val image: String? = null,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double? = null,
    val height: Double? = null,
    val rotation: Double? = null,
    val hover: List<String> = emptyList(),
    val click: String? = null,
    val clickAction: String? = null,
    val dependency: String? = null,
    val data: FtbSnbtData = FtbSnbtData(),
)

@Serializable
data class FtbItemStack(
    val itemId: String,
    /** Null means the source did not specify a count. */
    val count: Long? = null,
    /** Contains the original legacy tag/components and any custom icon fields. */
    /** Original item compound, including legacy tag or modern components when present. */
    val data: FtbSnbtData? = null,
)

@Serializable
data class FtbQuestDiagnostic(
    val code: String,
    val path: String? = null,
    val objectId: String? = null,
    val reference: String? = null,
    val message: String,
)

@Serializable
data class FtbQuestReadResult(
    val book: FtbQuestBook,
    val diagnostics: List<FtbQuestDiagnostic> = emptyList(),
)
