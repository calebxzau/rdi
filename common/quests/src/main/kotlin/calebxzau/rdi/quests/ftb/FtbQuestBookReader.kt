package calebxzau.rdi.quests.ftb

import calebxzau.rdi.quests.ftb.model.*
import calebxzau.rdi.quests.ftb.snbt.FtbSnbt
import calebxzau.rdi.quests.ftb.snbt.FtbSnbtData
import net.benwoodworth.knbt.*
import java.math.BigInteger
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import kotlin.math.min

/** Reads an FTB Quests `config/ftbquests/quests` directory as a pure data operation. */
object FtbQuestBookReader {
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
    private const val MAX_FILES = 10_000

    fun read(questsDir: Path, source: FtbQuestSource = FtbQuestSource()): Result<FtbQuestReadResult> = try {
        Result.success(Reader(questsDir, source).read())
    } catch (exception: java.util.concurrent.CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    private class Reader(private val root: Path, private val source: FtbQuestSource) {
        private var totalBytes = 0L
        private var fileCount = 0
        private val diagnostics = mutableListOf<FtbQuestDiagnostic>()
        private val persistentIds = linkedMapOf<String, String>()
        private val objectKinds = linkedMapOf(ONE_ID to ObjectKind.Book)

        fun read(): FtbQuestReadResult {
            require(Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "quests path is not a directory: $root" }
            require(!Files.isSymbolicLink(root)) { "quests path must not be a symbolic link: $root" }
            val data = withPath("data.snbt") {
                parse(readRequired(root.resolve("data.snbt")), "data.snbt")
            }
            val version = withPath("data.snbt") {
                data.integralInt("version") ?: error("missing integral version")
            }

            val groupsPath = root.resolve("chapter_groups.snbt")
            val groupRoot = if (Files.exists(groupsPath, LinkOption.NOFOLLOW_LINKS)) {
                withPath("chapter_groups.snbt") {
                    parse(readRegular(groupsPath), relative(groupsPath))
                }
            } else null
            val groups = groupRoot?.let { rootTag ->
                withPath("chapter_groups.snbt") {
                    rootTag.list("chapter_groups")?.mapIndexed { i, tag ->
                        val path = "chapter_groups.snbt/chapter_groups[$i]"
                        withPath(path) {
                            val c = tag.compound(path)
                            val id = c.requiredId(path)
                            register(id, path, ObjectKind.ChapterGroup)
                            FtbChapterGroup(
                                id = id,
                                title = c.string("title"),
                                subtitle = c.string("subtitle"),
                                icon = c.item("icon", path),
                                tags = c.stringList("tags", path),
                                orderIndex = c.int("order_index"),
                                data = FtbSnbtData(c),
                            )
                        }
                    } ?: emptyList()
                }
            } ?: emptyList()
            val groupData = FtbSnbtData(groupRoot?.without("chapter_groups") ?: NbtCompound(emptyMap()))

            val chapters = readChapters(groups)
            val tables = readTables()
            val languages = readLanguages()
            val book = FtbQuestBook(
                source = source,
                dataVersion = version,
                settings = FtbSnbtData(data),
                chapterGroups = groups,
                chapters = chapters,
                rewardTables = tables,
                languages = languages,
                chapterGroupsData = groupData,
                title = data.string("title"),
            )
            validateReferences(book)
            return FtbQuestReadResult(book, diagnostics.toList())
        }

        private fun readChapters(groups: List<FtbChapterGroup>): List<FtbChapter> {
            val dir = optionalDirectory("chapters") ?: return emptyList()
            val files = snbtFiles(dir)
            val chapters = files.map { file ->
                val relativePath = relative(file)
                withPath(relativePath) {
                    val c = parse(readRegular(file), relativePath)
                    val id = c.requiredId(relativePath)
                    register(id, relativePath, ObjectKind.Chapter)
                    val group = c["group"]?.let { groupTag ->
                        if (groupTag is NbtString && groupTag.value.isEmpty()) null
                        else c.ref("group", relativePath)
                    }
                    val chapter = FtbChapter(
                        id = id,
                        sourcePath = relativePath,
                        groupId = group?.takeUnless { it == ZERO_ID },
                        orderIndex = c.int("order_index"),
                        title = c.string("title"),
                        subtitle = c.stringList("subtitle", relativePath),
                        icon = c.item("icon", relativePath),
                        tags = c.stringList("tags", relativePath),
                        quests = c.list("quests")?.mapIndexed { i, tag ->
                            parseQuest(tag.compound("$relativePath/quests[$i]"), "$relativePath/quests[$i]")
                        } ?: emptyList(),
                        links = c.list("quest_links")?.mapIndexed { i, tag ->
                            parseLink(tag.compound("$relativePath/quest_links[$i]"), "$relativePath/quest_links[$i]")
                        } ?: emptyList(),
                        images = c.list("images")?.mapIndexed { i, tag ->
                            parseImage(tag.compound("$relativePath/images[$i]"), "$relativePath/images[$i]")
                        } ?: emptyList(),
                        data = FtbSnbtData(c.without("quests", "quest_links", "images")),
                    )
                    chapter.quests.forEach { quest ->
                        register(quest.id, "$relativePath/quests/${quest.id}", ObjectKind.Quest)
                        quest.tasks.forEach { register(it.id, "$relativePath/quests/${quest.id}/tasks/${it.id}", ObjectKind.Task) }
                    quest.rewards.forEach { registerRewardTree(it, "$relativePath/quests/${quest.id}/rewards") }
                    }
                    chapter.links.forEach { register(it.id, "$relativePath/quest_links/${it.id}", ObjectKind.Link) }
                    chapter.images.forEach { it.id?.let { id -> register(id, "$relativePath/images/$id", ObjectKind.Image) } }
                    chapter
                }
            }
            return chapters.sortedWith(
                compareBy<FtbChapter>(
                    { it.groupId != null },
                    { groups.indexOfFirst { group -> group.id == it.groupId }
                        .let { index -> if (index < 0) Int.MAX_VALUE else index } },
                    { it.orderIndex ?: 0 },
                    { it.sourcePath },
                ),
            )
        }

        private fun parseQuest(c: NbtCompound, path: String): FtbQuest {
            val id = c.requiredId(path)
            val dependencies = c.refList("dependencies", path)
            return FtbQuest(
                id = id,
                title = c.string("title"), subtitle = c.string("subtitle"),
                description = c.stringList("description", path),
                icon = c.item("icon", path), tags = c.stringList("tags", path),
                x = c.double("x", path) ?: 0.0,
                y = c.double("y", path) ?: 0.0,
                shape = c.string("shape"), size = c.double("size", path),
                dependencies = dependencies,
                dependencyRequirement = c.string("dependency_requirement"),
                minRequiredDependencies = c.int("min_required_dependencies"),
                optional = c.bool("optional") ?: false,
                repeatable = c.bool("can_repeat"),
                tasks = c.list("tasks")?.mapIndexed { i, tag -> parseTask(tag.compound("$path/tasks[$i]"), "$path/tasks[$i]") } ?: emptyList(),
                rewards = c.list("rewards")?.mapIndexed { i, tag -> parseReward(tag.compound("$path/rewards[$i]"), "$path/rewards[$i]", true) } ?: emptyList(),
                data = FtbSnbtData(c.without("tasks", "rewards")),
            )
        }

        private fun parseTask(c: NbtCompound, path: String): FtbTask {
            val id = c.requiredId(path)
            val type = c.string("type") ?: "item"
            val itemType = type == "item" || type == "ftbquests:item"
            return FtbTask(
                id = id,
                type = type,
                title = c.string("title"),
                icon = c.item("icon", path),
                tags = c.stringList("tags", path),
                optional = c.bool("optional_task") ?: false,
                item = if (itemType) c.item("item", path) else null,
                count = if (itemType) c.long("count", path) else null,
                data = FtbSnbtData(c),
            )
        }

        private fun parseReward(c: NbtCompound, path: String, requiresId: Boolean): FtbReward {
            val id = c.optionalId(path)
            if (requiresId && id == null) error("$path: reward is missing id")
            val type = c.string("type") ?: "item"
            val itemType = type == "item" || type == "ftbquests:item"
            val tableType = type in setOf(
                "random", "ftbquests:random",
                "choice", "ftbquests:choice",
                "all_table", "ftbquests:all_table",
            )
            val tableData = if (tableType) c.compoundOrNull("table_data", path) else null
            return FtbReward(
                id = id,
                type = type,
                title = c.string("title"),
                icon = c.item("icon", path),
                tags = c.stringList("tags", path),
                item = if (itemType) c.item("item", path) else null,
                count = if (itemType) c.long("count", path) else null,
                tableId = if (tableType) c.ref("table_id", path) else null,
                inlineTable = tableData?.let { parseTable(it, "$path/table_data", null, false) },
                data = FtbSnbtData(c),
            )
        }

        private fun parseTable(c: NbtCompound, path: String, id: String?, topLevel: Boolean): FtbRewardTable {
            val entries = c.list("rewards")?.mapIndexed { i, tag ->
                val entry = tag.compound("$path/rewards[$i]")
                FtbWeightedReward(parseReward(entry, "$path/rewards[$i]", false), entry.float("weight", "$path/rewards[$i]") ?: 1f)
            } ?: emptyList()
            return FtbRewardTable(
                id = id,
                sourcePath = if (topLevel) path else null,
                orderIndex = c.int("order_index"),
                title = c.string("title"),
                icon = c.item("icon", path),
                tags = c.stringList("tags", path),
                entries = entries,
                emptyWeight = c.float("empty_weight", path) ?: 0f,
                lootSize = c.int("loot_size"),
                data = FtbSnbtData(c.without("rewards")),
            )
        }

        private fun readTables(): List<FtbRewardTable> {
            val dir = optionalDirectory("reward_tables") ?: return emptyList()
            val tables = snbtFiles(dir).map { file ->
                val path = relative(file)
                withPath(path) {
                    val c = parse(readRegular(file), path)
                    val id = c.requiredId(path)
                    register(id, path, ObjectKind.RewardTable)
                    parseTable(c, path, id, true)
                }
            }.sortedWith(compareBy({ it.orderIndex ?: 0 }, { it.sourcePath ?: "" }))
            // Match FTB Quests refreshRewardTableRewardIDs: sorted table rewards replace earlier IDs.
            tables.forEach { table ->
                table.entries.forEachIndexed { index, entry ->
                    registerTableRewardTree(entry.reward, "${table.sourcePath}/rewards[$index]")
                }
            }
            return tables
        }

        private fun readLanguages(): Map<String, FtbSnbtData> {
            val dir = optionalDirectory("lang") ?: return emptyMap()
            val result = linkedMapOf<String, FtbSnbtData>()
            snbtFiles(dir).forEach { file ->
                val locale = file.fileName.toString().substringBeforeLast('.').lowercase(Locale.ROOT)
                withPath(relative(file)) {
                    require(result.put(locale, FtbSnbtData(parse(readRegular(file), relative(file)))) == null) { "duplicate normalized locale '$locale'" }
                }
            }
            return result
        }

        private fun parseLink(c: NbtCompound, path: String): FtbQuestLink = FtbQuestLink(
            id = c.requiredId(path),
            linkedQuestId = c.ref("linked_quest", path),
            x = c.double("x", path) ?: 0.0,
            y = c.double("y", path) ?: 0.0,
            shape = c.string("shape"),
            size = c.double("size", path),
            data = FtbSnbtData(c),
        )

        private fun parseImage(c: NbtCompound, path: String): FtbChapterImage {
            val hover = c.stringList("hover", path)
            return FtbChapterImage(
                id = c.optionalId(path),
                image = c.string("image"),
                title = c.string("title") ?: if ("hover" in c) hover.joinToString("\\n") else null,
                x = c.double("x", path) ?: 0.0,
                y = c.double("y", path) ?: 0.0,
                width = c.double("width", path),
                height = c.double("height", path),
                rotation = c.double("rotation", path),
                hover = hover,
                click = c.string("click"),
                clickAction = c.string("click_action"),
                dependency = c.ref("dependency", path),
                data = FtbSnbtData(c),
            )
        }

        private fun validateReferences(book: FtbQuestBook) {
            val taggedObjects = linkedMapOf<String, FtbSnbtData>()
            fun collectReward(reward: FtbReward) {
                reward.id?.let { taggedObjects[it] = reward.data }
            }
            taggedObjects[ONE_ID] = book.settings
            book.chapterGroups.forEach { taggedObjects[it.id] = it.data }
            book.chapters.forEach { chapter ->
                taggedObjects[chapter.id] = chapter.data
                chapter.quests.forEach { quest ->
                    taggedObjects[quest.id] = quest.data
                    quest.tasks.forEach { taggedObjects[it.id] = it.data }
                    quest.rewards.forEach(::collectReward)
                }
                chapter.links.forEach { taggedObjects[it.id] = it.data }
                chapter.images.forEach { image -> image.id?.let { taggedObjects[it] = image.data } }
            }
            book.rewardTables.forEach { table ->
                table.id?.let { taggedObjects[it] = table.data }
            }
            book.rewardTables.forEach { table ->
                table.entries.forEach { collectReward(it.reward) }
            }
            val tags = taggedObjects.flatMap { (id, data) -> data.nbt.stringListOrEmpty("tags").map { it to id } }
                .groupBy({ it.first }, { it.second }).mapValues { (_, owners) -> owners.toSet() }

            fun check(ref: String, path: String, owner: String, allowed: Set<ObjectKind>) {
                val id = if (ref.startsWith('#')) {
                    val matches = tags[ref.substring(1)].orEmpty()
                    when (matches.size) {
                        0 -> {
                            diagnostics += FtbQuestDiagnostic("dangling_reference", path, owner, ref, "tag reference does not resolve in this quest book")
                            return
                        }
                        1 -> matches.single()
                        else -> {
                            diagnostics += FtbQuestDiagnostic("ambiguous_tag", path, owner, ref, "tag reference matches multiple objects")
                            return
                        }
                    }
                } else ref
                val kind = objectKinds[id]
                if (kind == null) {
                    diagnostics += FtbQuestDiagnostic("dangling_reference", path, owner, ref, "reference does not resolve in this quest book")
                } else if (kind !in allowed) {
                    diagnostics += FtbQuestDiagnostic("reference_type_mismatch", path, owner, ref, "reference resolves to $kind, which is not valid here")
                }
            }
            fun checkReward(reward: FtbReward, path: String) {
                reward.tableId?.let { check(it, path, reward.id ?: path, setOf(ObjectKind.RewardTable)) }
                reward.inlineTable?.entries?.forEach { checkReward(it.reward, "$path/table_data") }
            }

            for (chapter in book.chapters) {
                chapter.groupId?.let { check(it, chapter.sourcePath, chapter.id, setOf(ObjectKind.ChapterGroup)) }
                for (quest in chapter.quests) {
                    quest.dependencies.forEach {
                        check(it, "${chapter.sourcePath}/quests/${quest.id}/dependencies", quest.id, QUEST_DEPENDENCY_KINDS)
                    }
                    quest.rewards.forEach { checkReward(it, "${chapter.sourcePath}/quests/${quest.id}/rewards") }
                }
                chapter.links.forEach { link -> link.linkedQuestId?.let { check(it, "${chapter.sourcePath}/quest_links/${link.id}", link.id, setOf(ObjectKind.Quest)) } }
                chapter.images.forEach { image -> image.dependency?.let { check(it, "${chapter.sourcePath}/images/${image.id}", image.id ?: chapter.id, setOf(ObjectKind.Quest)) } }
            }
            book.rewardTables.forEach { table -> table.entries.forEach { checkReward(it.reward, "reward_table/${table.id}/rewards") } }

        }

        private fun registerRewardTree(reward: FtbReward, path: String) {
            reward.id?.let { register(it, "$path/$it", ObjectKind.Reward) }
        }

        private fun registerTableRewardTree(reward: FtbReward, path: String) {
            reward.id?.let { registerLastWins(it, "$path/$it", ObjectKind.Reward) }
        }

        private fun register(id: String, path: String, kind: ObjectKind) {
            require(id !in setOf(ZERO_ID, ONE_ID)) { "$path: reserved object id $id" }
            val firstPath = persistentIds.putIfAbsent(id, path)
            require(firstPath == null) { "$path: duplicate object id $id (first at $firstPath)" }
            objectKinds[id] = kind
        }

        private fun registerLastWins(id: String, path: String, kind: ObjectKind) {
            require(id !in setOf(ZERO_ID, ONE_ID)) { "$path: reserved object id $id" }
            objectKinds[id] = kind
        }
        private fun optionalDirectory(name: String): Path? { val p = root.resolve(name); if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return null; require(Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(p)) { "$name is not a directory" }; return p }
        private fun snbtFiles(dir: Path): List<Path> = Files.list(dir).use { stream ->
            val entries = ArrayList<Path>()
            val iterator = stream.iterator()
            var visited = 0
            while (iterator.hasNext()) {
                require(++visited <= MAX_FILES) { "too many directory entries in ${relative(dir)}" }
                val file = iterator.next()
                require(!Files.isSymbolicLink(file)) {
                    "symbolic-link quest input is not allowed: ${relative(file)}"
                }
                val isSnbt = file.fileName.toString()
                    .substringAfterLast('.', "")
                    .equals("snbt", true)
                if (isSnbt) {
                    require(Files.isRegularFile(file)) {
                        "expected regular file ${relative(file)}"
                    }
                    entries.add(file)
                }
            }
            entries.sortedBy { it.fileName.toString() }
        }

        private fun readRequired(path: Path): String {
            require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                "missing required file ${relative(path)}"
            }
            return readRegular(path)
        }

        private fun readRegular(path: Path): String {
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path)) {
                "expected regular file ${relative(path)}"
            }
            val initialSize = Files.size(path)
            val remainingBytes = MAX_TOTAL_BYTES - totalBytes
            require(remainingBytes > 0) { "quest input exceeds total size limit" }
            require(initialSize <= MAX_FILE_BYTES) { "file too large ${relative(path)}" }
            require(initialSize <= remainingBytes) { "quest input exceeds total size limit" }
            require(++fileCount <= MAX_FILES) { "too many quest files" }
            val maximum = min(MAX_FILE_BYTES, remainingBytes)
            val bytes = ByteArrayOutputStream(min(maximum, 8192L).toInt())
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val remaining = maximum - bytes.size()
                    if (remaining < 0) break
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining + 1).toInt())
                    if (read < 0) break
                    bytes.write(buffer, 0, read)
                    if (bytes.size().toLong() > maximum) {
                        error("file too large ${relative(path)}")
                    }
                }
            }
            totalBytes += bytes.size().toLong()
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
            } catch (exception: Exception) {
                throw IllegalArgumentException("${relative(path)}: invalid UTF-8", exception)
            }
        }
        private fun parse(text: String, path: String): NbtCompound = FtbSnbt.parse(text).getOrElse { throw IllegalArgumentException("$path: ${it.message}", it) }
        private inline fun <T> withPath(path: String, block: () -> T): T = try {
            block()
        } catch (exception: java.util.concurrent.CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception.message.orEmpty().startsWith("$path:")) throw exception
            throw IllegalArgumentException("$path: ${exception.message}", exception)
        }
        private fun relative(path: Path): String = root.relativize(path).toString().replace('\\', '/')
    }

    private const val ZERO_ID = "0000000000000000"
    private const val ONE_ID = "0000000000000001"
    private enum class ObjectKind { Book, ChapterGroup, Chapter, Quest, Task, Reward, Link, Image, RewardTable }
    private val QUEST_DEPENDENCY_KINDS = setOf(
        ObjectKind.Book,
        ObjectKind.ChapterGroup,
        ObjectKind.Chapter,
        ObjectKind.Quest,
        ObjectKind.Task,
        ObjectKind.Link,
    )

    private fun NbtTag?.asString(): String? = (this as? NbtString)?.value
    private fun NbtCompound.string(name: String): String? = when (val tag = this[name]) {
        null -> null
        is NbtString -> tag.value
        else -> error("field '$name' must be a string")
    }
    private fun NbtCompound.bool(name: String): Boolean? = when (val t = this[name]) { null -> null; is NbtByte -> t.value.toInt() != 0; else -> error("field '$name' must be boolean") }
    private fun NbtCompound.double(name: String, path: String): Double? = this[name]?.let { tag ->
        val value = try {
            tag.number().toDouble()
        } catch (exception: Exception) {
            throw IllegalArgumentException("$path: field '$name' must be numeric", exception)
        }
        require(value.isFinite()) { "$path: field '$name' must be finite" }
        value
    }

    private fun NbtCompound.float(name: String, path: String): Float? = double(name, path)?.toFloat()?.also {
        require(it.isFinite()) { "$path: field '$name' must be finite" }
    }
    private fun NbtCompound.int(name: String): Int? = integralInt(name)
    private fun NbtCompound.integralInt(name: String): Int? = this[name]?.let { tag ->
        when (tag) {
            is NbtByte -> tag.value.toInt()
            is NbtShort -> tag.value.toInt()
            is NbtInt -> tag.value
            is NbtLong -> tag.value.toInt().also { require(tag.value in Int.MIN_VALUE..Int.MAX_VALUE) }
            else -> error("field '$name' must be an integral number")
        }
    }
    private fun NbtCompound.long(name: String, path: String): Long? = this[name]?.let { tag -> integralLong(tag, "$path: field '$name'") }
    private fun integralLong(tag: NbtTag, context: String): Long = when (tag) {
        is NbtByte -> tag.value.toLong()
        is NbtShort -> tag.value.toLong()
        is NbtInt -> tag.value.toLong()
        is NbtLong -> tag.value
        else -> error("$context must be an integral number")
    }
    private fun NbtTag.number(): Number = when (this) { is NbtByte -> value; is NbtShort -> value; is NbtInt -> value; is NbtLong -> value; is NbtFloat -> value; is NbtDouble -> value; else -> error("expected numeric value") }
    private fun NbtCompound.list(name: String): List<NbtTag>? = when (val t = this[name]) { null -> null; is NbtList<*> -> t.toList(); else -> error("field '$name' must be a list") }
    private fun NbtCompound.compound(name: String): NbtCompound = compoundOrNull(name, name) ?: error("missing compound '$name'")
    private fun NbtCompound.compoundOrNull(name: String, path: String): NbtCompound? = when (val t = this[name]) { null -> null; is NbtCompound -> t; else -> error("$path: field '$name' must be a compound") }
    private fun NbtTag.compound(path: String): NbtCompound = this as? NbtCompound ?: error("$path must be a compound")
    private fun NbtCompound.stringList(name: String, path: String): List<String> = when (val t = this[name]) { null -> emptyList(); is NbtString -> listOf(t.value); is NbtList<*> -> t.map { (it as? NbtString)?.value ?: error("$path: '$name' must contain strings") }; else -> error("$path: '$name' must be string or list") }
    private fun NbtCompound.refList(name: String, path: String): List<String> = when (val t = this[name]) {
        null -> emptyList()
        is NbtList<*> -> t.map { normalizeRef((it as? NbtString)?.value ?: error("$path: '$name' must contain strings"), path, name) }
        is NbtIntArray -> t.map { numericId(it.toLong(), "$path: field '$name'") }
        is NbtLongArray -> t.map { numericId(it, "$path: field '$name'") }
        else -> error("$path: '$name' must be a list or integer array")
    }
    private fun NbtCompound.ref(name: String, path: String): String? = this[name]?.let { tag ->
        when (tag) {
            is NbtString -> normalizeRef(tag.value, path, name)
            else -> numericId(integralLong(tag, "$path: field '$name'"), "$path: field '$name'")
        }
    }
    private fun normalizeRef(value: String, path: String, field: String): String {
        val context = "$path: field '$field'"
        if (value.startsWith('#')) {
            val body = value.substring(1)
            return if (body.isNotEmpty() && body.matches(Regex("[0-9a-fA-F]+"))) canonicalId(body, context) else value
        }
        return canonicalId(value, context)
    }
    private fun NbtCompound.requiredId(path: String): String = this["id"]?.let { idTag(it, "$path: id") } ?: error("$path: missing id")
    private fun NbtCompound.optionalId(path: String): String? = this["id"]?.let { idTag(it, "$path: id") }
    private fun idTag(tag: NbtTag, context: String): String = when (tag) {
        is NbtString -> canonicalId(tag.value.removePrefix("#"), context)
        else -> numericId(integralLong(tag, context), context)
    }
    private fun numericId(value: Long, @Suppress("UNUSED_PARAMETER") context: String): String = java.lang.Long.toUnsignedString(value, 16).uppercase(Locale.ROOT).padStart(16, '0')
    private fun canonicalId(raw: String, context: String): String {
        val text = raw.removePrefix("0x").removePrefix("0X")
        require(text.matches(Regex("[0-9a-fA-F]+"))) { "$context must be a hexadecimal object id" }
        val n = BigInteger(text, 16)
        require(n.signum() >= 0 && n.bitLength() <= 64) { "$context is outside unsigned 64-bit range" }
        return n.toString(16).uppercase(Locale.ROOT).padStart(16, '0')
    }
    private fun NbtCompound.item(name: String, path: String): FtbItemStack? {
        val tag = this[name] ?: return null
        if (tag is NbtString) return FtbItemStack(tag.value, null, null)
        val c = tag as? NbtCompound ?: error("$path: field '$name' must be item compound or string")
        val id = c.string("id") ?: error("$path: item '$name' is missing id")
        return FtbItemStack(id, c.long("count", path) ?: c.long("Count", path), FtbSnbtData(c))
    }
    private fun NbtCompound.stringListOrEmpty(name: String): List<String> =
        (this[name] as? NbtList<*>)?.mapNotNull { (it as? NbtString)?.value }.orEmpty()
    private fun NbtCompound.without(vararg keys: String): NbtCompound = NbtCompound(filterKeys { it !in keys })
}
