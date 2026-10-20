package calebxzau.rdi.questviewer

import calebxzau.rdi.quests.ftb.model.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

class PreviewIconTest {
    @Test
    fun checkmarkDefaultsWorkWithoutExportedAssetsAndReachQuestAndLinkNodes() {
        for (type in listOf("checkmark", "ftbquests:checkmark")) {
            val task = FtbTask("task", type = type)
            val quest = FtbQuest("quest", tasks = listOf(task))
            val chapter = FtbChapter("chapter", "chapters/chapter.snbt", quests = listOf(quest),
                links = listOf(FtbQuestLink("link", quest.id, y = 2.0)))
            val data = QuestPreviewData(FtbQuestBook(dataVersion = 13, chapters = listOf(chapter)),
                PreviewAssets(Path.of(".")), emptyList())

            assertEquals(PreviewIcon.Checkmark, task.previewIcon())
            assertEquals(PreviewIcon.Checkmark, data.questIcon(quest))
            val graph = data.graph(chapter)
            assertEquals(2, graph.nodes.size)
            graph.nodes.forEach { assertEquals(PreviewIcon.Checkmark, it.icon) }
            assertNull(FtbTask("unknown", type = "example:checkmark").previewIcon())
        }
    }

    @Test
    fun explicitIconsAndItemTasksKeepTheirPrecedence() {
        val stone = FtbItemStack("minecraft:stone")
        val diamond = FtbItemStack("minecraft:diamond")
        val data = QuestPreviewData(FtbQuestBook(dataVersion = 13), PreviewAssets(Path.of(".")), emptyList())
        val checkmark = FtbTask("check", type = "checkmark")

        assertEquals(PreviewIcon.Item(stone.itemId), checkmark.copy(icon = stone).previewIcon())
        assertEquals(PreviewIcon.Item(stone.itemId), FtbTask("item", type = "item", item = stone).previewIcon())
        assertEquals(PreviewIcon.Item(diamond.itemId), data.questIcon(
            FtbQuest("quest", icon = diamond, tasks = listOf(checkmark))))
        assertEquals(PreviewIcon.Checkmark, data.questIcon(FtbQuest("quest", tasks = listOf(
            checkmark, FtbTask("item", type = "item", item = stone)))))
    }

    @Test
    fun rewardDefaultsUseExperienceBottleAndDiceIncludingTableBackedRandomRewards() {
        for (prefix in listOf("", "ftbquests:")) {
            assertEquals(PreviewIcon.Item("minecraft:experience_bottle"),
                FtbReward(type = "${prefix}xp_levels").previewIcon())
            assertEquals(PreviewIcon.Dice, FtbReward(type = "${prefix}random").previewIcon())
            assertEquals(PreviewIcon.Dice,
                FtbReward(type = "${prefix}random", tableId = "729876553F70D5DC").previewIcon())
            for (type in listOf("${prefix}xp_levels", "${prefix}random")) {
                val icon = FtbItemStack("minecraft:diamond")
                assertEquals(PreviewIcon.Item(icon.itemId), FtbReward(type = type, icon = icon).previewIcon())
            }
        }
        val item = FtbItemStack("minecraft:copper_ingot")
        assertEquals(PreviewIcon.Item(item.itemId), FtbReward(type = "item", item = item).previewIcon())
        assertNull(FtbReward(type = "example:random").previewIcon())
        assertNull(FtbReward(type = "example:xp_levels").previewIcon())
    }

    @Test
    fun bundledDiceIsADecodableTransparentPng() {
        val image = requireNotNull(javaClass.getResourceAsStream(BuiltinQuestIcons.DICE_RESOURCE)).use {
            assertNotNull(ImageIO.read(it))
        }
        assertEquals(16, image.width)
        assertEquals(16, image.height)
        assertTrue(image.colorModel.hasAlpha())
    }

    @Test
    fun bundledCheckmarkIsADecodableTransparentPng() {
        val image = requireNotNull(javaClass.getResourceAsStream(BuiltinQuestIcons.CHECKMARK_RESOURCE)).use {
            assertNotNull(ImageIO.read(it))
        }
        assertEquals(16, image.width)
        assertEquals(16, image.height)
        assertTrue(image.colorModel.hasAlpha())
    }
}
