package calebxzau.rdi.quests.ftb

import calebxzau.rdi.quests.ftb.model.FtbChapter
import calebxzau.rdi.quests.ftb.model.FtbQuest
import calebxzau.rdi.quests.ftb.model.FtbQuestBook
import calebxzau.rdi.quests.ftb.model.FtbReward
import calebxzau.rdi.quests.ftb.model.FtbRewardTable
import calebxzau.rdi.quests.ftb.model.FtbTask
import calebxzau.rdi.quests.ftb.model.FtbChapterImage
import net.benwoodworth.knbt.NbtList
import net.benwoodworth.knbt.NbtString
import java.util.Locale

/** Applies FTB Quests language entries to a copy of a quest book's typed fields. */
public object FtbQuestLocalizer {
    private const val FALLBACK_LOCALE = "en_us"

    /** Returns a localized typed-field copy; raw book data and language compounds are shared unchanged. */
    public fun localize(book: FtbQuestBook, locale: String): FtbQuestBook =
        Resolver(book, locale).localize()

    private class Resolver(
        private val book: FtbQuestBook,
        locale: String,
    ) {
        private val requested = book.languages[locale.trim().lowercase(Locale.ROOT)]
        private val fallback = book.languages[FALLBACK_LOCALE]

        private fun selectedTag(key: String) = when {
            requested != null && key in requested.nbt -> requested.nbt[key]
            fallback != null && key in fallback.nbt -> fallback.nbt[key]
            else -> null
        }

        private fun text(key: String, original: String?): String? =
            (selectedTag(key) as? NbtString)?.value ?: original

        private fun lines(key: String, original: List<String>): List<String> {
            val tag = selectedTag(key) ?: return original
            if (tag !is NbtList<*>) return original
            val translated = ArrayList<String>(tag.size)
            for (value in tag) {
                translated += (value as? NbtString)?.value ?: return original
            }
            return translated
        }

        private fun localizeTask(task: FtbTask): FtbTask = task.copy(
            title = text("task.${task.id}.title", task.title),
        )

        private fun localizeTable(table: FtbRewardTable): FtbRewardTable = table.copy(
            title = table.id?.let { text("reward_table.${it}.title", table.title) } ?: table.title,
            entries = table.entries.map { entry ->
                entry.copy(reward = localizeReward(entry.reward))
            },
        )

        private fun localizeReward(reward: FtbReward): FtbReward = reward.copy(
            title = reward.id?.let { text("reward.${it}.title", reward.title) } ?: reward.title,
            inlineTable = reward.inlineTable?.let(::localizeTable),
        )

        private fun localizeQuest(quest: FtbQuest): FtbQuest = quest.copy(
            title = text("quest.${quest.id}.title", quest.title),
            subtitle = text("quest.${quest.id}.quest_subtitle", quest.subtitle),
            description = lines("quest.${quest.id}.quest_desc", quest.description),
            tasks = quest.tasks.map(::localizeTask),
            rewards = quest.rewards.map(::localizeReward),
        )

        private fun localizeChapter(chapter: FtbChapter): FtbChapter = chapter.copy(
            title = text("chapter.${chapter.id}.title", chapter.title),
            subtitle = lines("chapter.${chapter.id}.chapter_subtitle", chapter.subtitle),
            quests = chapter.quests.map(::localizeQuest),
            images = chapter.images.map(::localizeImage),
        )

        private fun localizeImage(image: FtbChapterImage): FtbChapterImage = image.copy(
            title = image.id?.let { text("image.${it}.title", image.title) } ?: image.title,
        )

        fun localize(): FtbQuestBook = book.copy(
            title = text("file.0000000000000001.title", book.title),
            chapterGroups = book.chapterGroups.map { group ->
                group.copy(title = text("chapter_group.${group.id}.title", group.title))
            },
            chapters = book.chapters.map(::localizeChapter),
            rewardTables = book.rewardTables.map(::localizeTable),
        )
    }
}
