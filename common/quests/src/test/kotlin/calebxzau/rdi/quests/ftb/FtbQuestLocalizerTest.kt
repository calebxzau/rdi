package calebxzau.rdi.quests.ftb

import calebxzau.rdi.quests.ftb.model.FtbChapter
import calebxzau.rdi.quests.ftb.model.FtbChapterGroup
import calebxzau.rdi.quests.ftb.model.FtbChapterImage
import calebxzau.rdi.quests.ftb.model.FtbQuest
import calebxzau.rdi.quests.ftb.model.FtbQuestBook
import calebxzau.rdi.quests.ftb.model.FtbQuestSource
import calebxzau.rdi.quests.ftb.model.FtbReward
import calebxzau.rdi.quests.ftb.model.FtbRewardTable
import calebxzau.rdi.quests.ftb.model.FtbTask
import calebxzau.rdi.quests.ftb.model.FtbWeightedReward
import calebxzau.rdi.quests.ftb.snbt.FtbSnbtData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.benwoodworth.knbt.NbtCompound
import net.benwoodworth.knbt.NbtInt
import net.benwoodworth.knbt.NbtList
import net.benwoodworth.knbt.NbtString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FtbQuestLocalizerTest {
    @Test
    fun localizesAllTypedFieldsRecursivelyWithEnglishFallback(): Unit {
        val nested = FtbReward(id = "nested", title = "raw nested")
        val inline = FtbRewardTable(
            id = null,
            title = "raw inline table",
            entries = listOf(FtbWeightedReward(nested)),
        )
        val book = testBook(
            requested = mapOf(
                "file.0000000000000001.title" to NbtString("任务书"),
                "chapter_group.group.title" to NbtString("组"),
                "chapter.chapter.title" to NbtString("章节"),
                "chapter.chapter.chapter_subtitle" to strings("章节副标题"),
                "quest.quest.title" to NbtString("任务"),
                "quest.quest.quest_desc" to strings("描述", ""),
                "task.task.title" to NbtString("任务目标"),
                "reward.reward.title" to NbtString("奖励"),
                "reward_table.table.title" to NbtString("奖励表"),
                "image.image.title" to NbtString("图片"),
            ),
            english = mapOf(
                "quest.quest.quest_subtitle" to NbtString("English subtitle"),
                "reward.nested.title" to NbtString("Nested reward"),
            ),
            chapterGroup = FtbChapterGroup("group", title = "raw group", subtitle = "raw group subtitle"),
            chapter = FtbChapter(
                id = "chapter",
                sourcePath = "chapters/chapter.snbt",
                title = "raw chapter",
                subtitle = listOf("raw subtitle"),
                quests = listOf(
                    FtbQuest(
                        id = "quest",
                        title = "raw quest",
                        subtitle = "raw quest subtitle",
                        description = listOf("raw description"),
                        tasks = listOf(FtbTask("task", title = "raw task")),
                        rewards = listOf(FtbReward("reward", title = "raw reward", inlineTable = inline)),
                    ),
                ),
                images = listOf(
                    FtbChapterImage(id = "image", image = "resource:image", title = "raw image"),
                    FtbChapterImage(image = "resource:untitled", title = "raw untitled image"),
                ),
            ),
            table = FtbRewardTable(
                id = "table",
                title = "raw table",
                entries = listOf(FtbWeightedReward(FtbReward("table-reward", title = "raw table reward"))),
            ),
        )

        val localized = FtbQuestLocalizer.localize(book, " ZH_CN ")
        val group = localized.chapterGroups.single()
        val chapter = localized.chapters.single()
        val quest = chapter.quests.single()

        assertEquals("任务书", localized.title)
        assertEquals("组", group.title)
        assertEquals("raw group subtitle", group.subtitle)
        assertEquals("章节", chapter.title)
        assertEquals(listOf("章节副标题"), chapter.subtitle)
        assertEquals("任务", quest.title)
        assertEquals("English subtitle", quest.subtitle)
        assertEquals(listOf("描述", ""), quest.description)
        assertEquals("任务目标", quest.tasks.single().title)
        assertEquals("奖励", quest.rewards.single().title)
        assertEquals("raw inline table", quest.rewards.single().inlineTable?.title)
        assertEquals("Nested reward", quest.rewards.single().inlineTable?.entries?.single()?.reward?.title)
        assertEquals("奖励表", localized.rewardTables.single().title)
        assertEquals("raw table reward", localized.rewardTables.single().entries.single().reward.title)
        assertEquals("图片", chapter.images.first().title)
        assertEquals("raw untitled image", chapter.images.last().title)
    }

    @Test
    fun requestedEmptyValueAndMalformedValueDoNotUseEnglishFallback(): Unit {
        val book = testBook(
            requested = mapOf(
                "quest.quest.title" to NbtString(""),
                "quest.quest.quest_desc" to strings(),
                "task.task.title" to NbtInt(42),
            ),
            english = mapOf(
                "quest.quest.title" to NbtString("English title"),
                "quest.quest.quest_desc" to strings("English description"),
                "task.task.title" to NbtString("English task"),
            ),
            chapter = FtbChapter(
                id = "chapter",
                sourcePath = "chapter.snbt",
                quests = listOf(
                    FtbQuest(
                        id = "quest",
                        title = "raw title",
                        description = listOf("raw description"),
                        tasks = listOf(FtbTask("task", title = "raw task")),
                    ),
                ),
            ),
        )

        val quest = FtbQuestLocalizer.localize(book, "zh_cn").chapters.single().quests.single()
        assertEquals("", quest.title)
        assertEquals(emptyList(), quest.description)
        assertEquals("raw task", quest.tasks.single().title)
    }

    @Test
    fun preservesRawBookAndNonTextFieldsAndSerializesLocalizedPreview(): Unit {
        val settings = data("fallback_locale" to NbtString("fr_fr"), "number" to NbtInt(7))
        val languages = mapOf("zh_cn" to data("quest.quest.title" to NbtString("中文")))
        val task = FtbTask("task", title = "raw task", data = data("custom" to NbtInt(9)))
        val original = testBook(
            settings = settings,
            languages = languages,
            chapter = FtbChapter(
                id = "chapter",
                sourcePath = "chapter.snbt",
                quests = listOf(FtbQuest("quest", title = "raw quest", tasks = listOf(task))),
                images = listOf(FtbChapterImage(image = "resource:untitled", title = "raw image")),
            ),
        )

        val localized = FtbQuestLocalizer.localize(original, "zh_cn")
        assertNotSame(original, localized)
        assertEquals("raw quest", original.chapters.single().quests.single().title)
        assertEquals("中文", localized.chapters.single().quests.single().title)
        assertSame(original.settings, localized.settings)
        assertSame(original.languages, localized.languages)
        assertSame(task.data, localized.chapters.single().quests.single().tasks.single().data)
        assertEquals("raw image", localized.chapters.single().images.single().title)
        assertEquals(9, (localized.chapters.single().quests.single().tasks.single().data.nbt["custom"] as NbtInt).value)

        val json = Json.parseToJsonElement(Json.encodeToString(localized)).jsonObject
        val title = json.getValue("chapters").jsonArray.single()
            .jsonObject.getValue("quests").jsonArray.single().jsonObject
            .getValue("title").jsonPrimitive.content
        assertEquals("中文", title)
    }

    @Test
    fun localizesConfiguredRealSamplesAgainstTheirLanguageEntries(): Unit {
        val configured = System.getenv("RDI_FTB_QUESTS_SAMPLES").orEmpty()
        assumeTrue(configured.isNotBlank(), "RDI_FTB_QUESTS_SAMPLES is not set")
        configured.split(java.io.File.pathSeparatorChar).filter(String::isNotBlank).forEach { configuredPath ->
            val result = FtbQuestBookReader.read(Path.of(configuredPath)).getOrThrow()
            val localized = FtbQuestLocalizer.localize(result.book, "zh_cn")
            val translations = result.book.languages["zh_cn"]?.nbt ?: return@forEach
            (translations["file.0000000000000001.title"] as? NbtString)?.let {
                assertEquals(it.value, localized.title)
            }
            val quests = localized.chapters.flatMap { it.quests }
            val target = quests.find { it.id == "000F1E507ACDFAAE" }
            if (target != null) {
                translations["quest.${target.id}.title"]?.let { value ->
                    assertEquals((value as NbtString).value, target.title)
                }
                translations["quest.${target.id}.quest_desc"]?.let { value ->
                    assertEquals((value as NbtList<*>).map { (it as NbtString).value }, target.description)
                }
            }
            val localizedTitleCount = quests.count { it.title != null && translations["quest.${it.id}.title"] is NbtString }
            assertTrue(localizedTitleCount > 0, "sample has no localized quest titles: $configuredPath")
            translations.entries.firstOrNull { (key, value) ->
                key.startsWith("image.") && key.endsWith(".title") && value is NbtString
            }?.let { (key, value) ->
                val imageId = key.removePrefix("image.").removeSuffix(".title")
                val image = localized.chapters.flatMap { it.images }.find { it.id == imageId }
                if (image != null) assertEquals((value as NbtString).value, image.title)
            }
            println("FTB localized sample=$configuredPath zhQuestTitles=$localizedTitleCount quests=${quests.size}")
        }
    }

    private fun testBook(
        requested: Map<String, net.benwoodworth.knbt.NbtTag> = emptyMap(),
        english: Map<String, net.benwoodworth.knbt.NbtTag> = emptyMap(),
        settings: FtbSnbtData = FtbSnbtData(),
        chapterGroup: FtbChapterGroup = FtbChapterGroup("group", title = "raw group", subtitle = "raw group subtitle"),
        chapter: FtbChapter = FtbChapter("chapter", "chapter.snbt"),
        table: FtbRewardTable = FtbRewardTable(id = "table", title = "raw table"),
        languages: Map<String, FtbSnbtData> = mapOf("zh_cn" to FtbSnbtData(NbtCompound(requested)), "en_us" to FtbSnbtData(NbtCompound(english))),
    ): FtbQuestBook = FtbQuestBook(
        source = FtbQuestSource(mcVersion = "1.22.0"),
        dataVersion = 14,
        settings = settings,
        chapterGroups = listOf(chapterGroup),
        chapters = listOf(chapter),
        rewardTables = listOf(table),
        languages = languages,
    )

    private fun data(vararg entries: Pair<String, net.benwoodworth.knbt.NbtTag>): FtbSnbtData =
        FtbSnbtData(NbtCompound(entries.toMap()))

    private fun strings(vararg values: String): NbtList<*> = NbtList(values.map(::NbtString))
}
