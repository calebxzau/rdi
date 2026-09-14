package calebxzau.rdi.quests.ftb

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.benwoodworth.knbt.NbtCompound
import net.benwoodworth.knbt.NbtLong
import net.benwoodworth.knbt.NbtString
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FtbQuestBookReaderTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun readsDefinitionsAcrossFilesAndPreservesReferencesAndLegacyItems(): Unit {
        val root = tempDir.resolve("quests").createDirectories()
        root.resolve("chapters").createDirectories()
        root.resolve("reward_tables").createDirectories()
        root.resolve("lang").createDirectories()
        root.resolve("data.snbt").writeText("{version:13, tags:[\"standard\"], unknown_root:{keep:1L}}")
        root.resolve("chapter_groups.snbt").writeText("""
            { chapter_groups: [{id:"0000000000000010", title:"Group"}] }
        """.trimIndent())
        root.resolve("chapters/a.snbt").writeText("""
            {
              id:"0000000000000020", group:"0000000000000010", order_index:2, filename:"a"
              quests:[{
                id:"0000000000000030", title:"First", dependencies:["FFFFFFFFFFFFFFFF", "0000000000000041", "#shared", "#standard"],
                can_repeat:true,
                tasks:[{id:"0000000000000031", optional_task:true,
                  item:{id:"minecraft:stone", Count:1b, tag:{display:{Name:"legacy"}}}}],
                rewards:[{id:"0000000000000032", type:"random", table_id:9007199254740993L,
                  table_data:{loot_size:2, rewards:[{item:{id:"minecraft:diamond"}}]}}]
              }]
              quest_links:[]
              images:[{image:"pack:chapter/banner", x:1d, y:2d, hover:["One",""], click:"old-action"}]
            }
        """.trimIndent())
        root.resolve("chapters/z.snbt").writeText("""
            {id:"0000000000000040", group:"", order_index:0, quests:[{
              id:"FFFFFFFFFFFFFFFF", tags:["shared"], tasks:[{id:"0000000000000041", type:"thirdparty:custom", custom_field:[I;1,2]}]
            }]}
        """.trimIndent())
        root.resolve("reward_tables/0000000000000050.snbt").writeText("""
            {id:"0020000000000001", order_index:0, rewards:[{item:{id:"minecraft:apple"}, weight:0f}]}
        """.trimIndent())
        root.resolve("lang/en_us.snbt").writeText("""
            {quest.0000000000000030.title:"Translated", quest.0000000000000030.quest_desc:["line","", "{missing.key}"]}
        """.trimIndent())

        val result = FtbQuestBookReader.read(root).getOrThrow()
        val book = result.book
        assertEquals(13, book.dataVersion)
        assertEquals(listOf("0000000000000040", "0000000000000020"), book.chapters.map { it.id })
        assertNull(book.chapters.first().groupId)
        assertEquals("0000000000000010", book.chapters.last().groupId)
        val first = book.chapters.last().quests.single()
        assertEquals(listOf("FFFFFFFFFFFFFFFF", "0000000000000041", "#shared", "#standard"), first.dependencies)
        assertTrue(first.repeatable == true)
        val task = first.tasks.single()
        assertEquals("item", task.type)
        assertTrue(task.optional)
        assertEquals(1L, task.item?.count)
        assertIs<NbtCompound>(task.item?.data?.nbt)
        assertEquals("legacy", (((task.item?.data?.nbt?.get("tag") as NbtCompound)["display"] as NbtCompound)["Name"] as NbtString).value)
        val reward = first.rewards.single()
        assertEquals("0020000000000001", reward.tableId)
        assertNull(reward.inlineTable?.entries?.single()?.reward?.id)
        assertEquals(2, reward.inlineTable?.lootSize)
        assertEquals("thirdparty:custom", book.chapters.first().quests.single().tasks.single().type)
        assertEquals(listOf("line", "", "{missing.key}"), (book.languages.getValue("en_us").nbt["quest.0000000000000030.quest_desc"] as net.benwoodworth.knbt.NbtList<*>).map { (it as NbtString).value })
        assertTrue(result.diagnostics.isEmpty(), "cross-chapter, task, tag and large numeric table references should resolve")
        assertEquals(NbtLong(1), (book.settings.nbt["unknown_root"] as NbtCompound)["keep"])
    }

    @Test
    fun readsModernItemComponentsAndImageClickDataAndSerializesPreviewJson(): Unit {
        val root = tempDir.resolve("modern").createDirectories()
        root.resolve("chapters").createDirectories()
        root.resolve("data.snbt").writeText("{version:14}")
        root.resolve("chapters/chapter.snbt").writeText("""
            {id:"0000000000000010", group:"0000000000000011", quests:[{
              id:"0000000000000011", optional:true,
              tasks:[{id:"0000000000000012", type:"item", item:{id:"minecraft:paper", count:2, components:{"minecraft:custom_name":'{"text":"Paper"}'}}}]
            }], images:[{id:"0000000000000013", image:"resource:test", click_action:"open_url", dependency:"0000000000000011"}]}
        """.trimIndent())

        val result = FtbQuestBookReader.read(root).getOrThrow()
        val taskItem = result.book.chapters.single().quests.single().tasks.single().item!!
        assertEquals(2L, taskItem.count)
        assertTrue("components" in taskItem.data!!.nbt)
        assertEquals("open_url", result.book.chapters.single().images.single().clickAction)
        val json = Json.encodeToString(result)
        val taskData = Json.parseToJsonElement(json).jsonObject
            .getValue("book").jsonObject
            .getValue("chapters").jsonArray.single().jsonObject
            .getValue("quests").jsonArray.single().jsonObject
            .getValue("tasks").jsonArray.single().jsonObject
            .getValue("data")
        assertTrue(taskData is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun reportsDanglingReferencesWithoutDroppingThemAndAllowsEmptyOptionalFolders(): Unit {
        val root = tempDir.resolve("partial").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("chapters").createDirectories().resolve("only.snbt").writeText("""
            {id:"0000000000000010", quests:[{id:"0000000000000011", dependencies:["0000000000000999", "#missing"]}]}
        """.trimIndent())
        val result = FtbQuestBookReader.read(root).getOrThrow()
        assertEquals(listOf("0000000000000999", "#missing"), result.book.chapters.single().quests.single().dependencies)
        assertEquals(2, result.diagnostics.size)
        assertTrue(result.diagnostics.all { it.code == "dangling_reference" })
        assertTrue(result.book.rewardTables.isEmpty())
        assertTrue(result.book.languages.isEmpty())
        assertTrue(result.book.chapterGroups.isEmpty())
    }

    @Test
    fun reportsResolvedReferencesWithTheWrongObjectKind(): Unit {
        val root = tempDir.resolve("wrong-reference-kind").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("chapters").createDirectories().resolve("chapter.snbt").writeText("""
            {id:"0000000000000010", group:"0000000000000011", quests:[{
              id:"0000000000000011", tasks:[{id:"0000000000000012"}],
              rewards:[{id:"0000000000000013", type:"random", table_id:"0000000000000012"}]
            }], quest_links:[{id:"0000000000000014", linked_quest:"0000000000000012"}],
            images:[{id:"0000000000000015", dependency:"0000000000000010"}]}
        """.trimIndent())
        root.resolve("chapter_groups.snbt").writeText("{chapter_groups:[{id:\"0000000000000020\"}]}")

        val result = FtbQuestBookReader.read(root).getOrThrow()
        assertEquals(4, result.diagnostics.size)
        assertTrue(result.diagnostics.all { it.code == "reference_type_mismatch" })
    }

    @Test
    fun rejectsMalformedFilesDuplicateIdsAndWrongKnownFieldTypes(): Unit {
        val root = tempDir.resolve("invalid").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("chapters").createDirectories()
        root.resolve("chapters/a.snbt").writeText("{id:\"0000000000000010\", quests:[{id:\"0000000000000011\"}]}")
        root.resolve("chapters/b.snbt").writeText("{id:\"0000000000000010\"}")
        val duplicate = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(duplicate?.message.orEmpty().contains("duplicate object id"))

        root.resolve("chapters/b.snbt").writeText("{id:\"0000000000000020\", quests:\"wrong-kind\"}")
        val wrongType = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(wrongType?.message.orEmpty().contains("quests"))

        root.resolve("chapters/b.snbt").writeText("{id: \"0000000000000020\", broken: [}")
        val malformed = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(malformed?.message.orEmpty().contains("chapters/b.snbt"))
    }

    @Test
    fun preservesLegacyTableRewardIdAbsenceAndZeroWeight(): Unit {
        val root = tempDir.resolve("table").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("reward_tables").createDirectories().resolve("table.snbt").writeText("""
            {id:"0000000000000010", rewards:[{item:{id:"minecraft:apple"}, weight:0f}]}
        """.trimIndent())
        val table = FtbQuestBookReader.read(root).getOrThrow().book.rewardTables.single()
        assertNull(table.lootSize)
        assertEquals(0f, table.entries.single().weight)
        assertNull(table.entries.single().reward.id)
    }

    @Test
    fun preservesUnknownTypeFieldsWithoutInterpretingThemAsItemData(): Unit {
        val root = tempDir.resolve("unknown").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("chapters").createDirectories().resolve("chapter.snbt").writeText("""
            {id:"0000000000000010", quests:[{id:"0000000000000011",
              tasks:[{id:"0000000000000012", type:"thirdparty:item", count:"many", item:{custom:"payload"}, custom_nan:NaNd}],
              rewards:[{id:"0000000000000013", type:"thirdparty:random", count:"many", item:{custom:"payload"}, table_id:"not-hex", table_data:"opaque"}]
            }]}
        """.trimIndent())
        val quest = FtbQuestBookReader.read(root).getOrThrow().book.chapters.single().quests.single()
        val task = quest.tasks.single()
        assertNull(task.item)
        assertNull(task.count)
        assertEquals("many", (task.data.nbt["count"] as NbtString).value)
        assertTrue("item" in task.data.nbt)
        val reward = quest.rewards.single()
        assertNull(reward.item)
        assertNull(reward.count)
        assertEquals("many", (reward.data.nbt["count"] as NbtString).value)
        assertTrue("item" in reward.data.nbt)
        val taskData = Json.parseToJsonElement(Json.encodeToString(FtbQuestBookReader.read(root).getOrThrow()))
            .jsonObject.getValue("book").jsonObject
            .getValue("chapters").jsonArray.single().jsonObject
            .getValue("quests").jsonArray.single().jsonObject
            .getValue("tasks").jsonArray.single().jsonObject
            .getValue("data").jsonObject
        assertEquals("many", taskData.getValue("count").jsonPrimitive.content)
    }

    @Test
    fun rejectsNonFiniteTypedLayoutValuesWithSourcePath(): Unit {
        val root = tempDir.resolve("non-finite").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("chapters").createDirectories().resolve("chapter.snbt").writeText(
            "{id:\"0000000000000010\", quests:[{id:\"0000000000000011\", x:NaNd}]}",
        )
        val failure = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("chapters/chapter.snbt"))
        assertTrue(failure?.message.orEmpty().contains("x"))
    }

    @Test
    fun acceptsExactNumericAndHashPrefixedIdsAndImplicitGroups(): Unit {
        val root = tempDir.resolve("numeric-id").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("chapter_groups.snbt").writeText("{chapter_groups:[{id:\"#10\"}]}")
        root.resolve("chapters").createDirectories().resolve("chapter.snbt").writeText(
            "{id:32, group:16, quests:[{id:\"#21\"}]}",
        )
        val book = FtbQuestBookReader.read(root).getOrThrow().book
        assertEquals("0000000000000010", book.chapterGroups.single().id)
        assertEquals("0000000000000010", book.chapters.single().groupId)
        assertEquals("0000000000000020", book.chapters.single().id)
        assertEquals("0000000000000021", book.chapters.single().quests.single().id)
    }

    @Test
    fun rejectsMalformedRequiredRootAndSnbtDirectories(): Unit {
        val root = tempDir.resolve("root-errors").createDirectories()
        root.resolve("data.snbt").writeText("{version:\"13\"}")
        val versionFailure = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(versionFailure?.message.orEmpty().contains("data.snbt"))

        root.resolve("data.snbt").writeText("{version:13}")
        root.resolve("chapter_groups.snbt").writeText("{chapter_groups:[]}")
        root.resolve("chapter_groups.snbt").writeText("{chapter_groups:\"wrong\"}")
        val groupsFailure = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(groupsFailure?.message.orEmpty().contains("chapter_groups.snbt"))

        Files.createDirectories(root.resolve("chapters"))
        root.resolve("chapter_groups.snbt").writeText("{chapter_groups:[]}")
        Files.createDirectories(root.resolve("chapters/not-a-file.snbt"))
        val directoryFailure = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(directoryFailure?.message.orEmpty().contains("not-a-file.snbt"))
    }

    @Test
    fun rejectsOversizedFileBeforeAllocatingItsWholeContents(): Unit {
        val root = tempDir.resolve("oversized").createDirectories()
        root.resolve("data.snbt").writeText("{version:13}")
        val chapters = root.resolve("chapters").createDirectories()
        val oversized = chapters.resolve("large.snbt")
        Files.newByteChannel(oversized, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.position(32L * 1024 * 1024 + 1)
            channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        }
        val failure = FtbQuestBookReader.read(root).exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("large.snbt"))
        assertTrue(failure?.message.orEmpty().contains("large") || failure?.message.orEmpty().contains("too large"))
    }

    @Test
    fun readsConfiguredRealPackSamplesWhenProvided(): Unit {
        val configured = System.getenv("RDI_FTB_QUESTS_SAMPLES").orEmpty()
        assumeTrue(configured.isNotBlank(), "RDI_FTB_QUESTS_SAMPLES is not set")
        val samples = configured.split(java.io.File.pathSeparatorChar).filter(String::isNotBlank).map { Path.of(it) }
        samples.forEach { sample ->
            val result = FtbQuestBookReader.read(sample).getOrThrow()
            val chapterFiles = Files.list(sample.resolve("chapters")).use { stream -> stream.filter { it.fileName.toString().endsWith(".snbt", true) }.count().toInt() }
            val tableFiles = if (Files.isDirectory(sample.resolve("reward_tables"))) Files.list(sample.resolve("reward_tables")).use { stream -> stream.filter { it.fileName.toString().endsWith(".snbt", true) }.count().toInt() } else 0
            assertEquals(chapterFiles, result.book.chapters.size)
            assertEquals(tableFiles, result.book.rewardTables.size)
            assertTrue(Json.parseToJsonElement(Json.encodeToString(result)) is kotlinx.serialization.json.JsonObject)
            println("FTB sample=$sample groups=${result.book.chapterGroups.size} chapters=${result.book.chapters.size} quests=${result.book.chapters.sumOf { it.quests.size }} tasks=${result.book.chapters.sumOf { chapter -> chapter.quests.sumOf { it.tasks.size } }} rewards=${result.book.chapters.sumOf { chapter -> chapter.quests.sumOf { it.rewards.size } }} tables=${result.book.rewardTables.size} images=${result.book.chapters.sumOf { it.images.size }} links=${result.book.chapters.sumOf { it.links.size }} locales=${result.book.languages.size} diagnostics=${result.diagnostics.size}")
            result.diagnostics.forEach { println("  ${it.code} ${it.path}: ${it.reference} ${it.message}") }
        }
    }

    @Test
    fun readRealQuest() {
        val message =
            FtbQuestBookReader.read("C:\\Users\\calebxzhou\\Documents\\rdi5ship\\mc\\versions\\6a9e67f81d0c4a8230366e10_1.1.1b\\config\\ftbquests\\quests".let {
                Path.of(it)
            }).getOrNull()

        Json.encodeToString(message).let { File("quest_read_test.json").writeText(it) }
    }
}
