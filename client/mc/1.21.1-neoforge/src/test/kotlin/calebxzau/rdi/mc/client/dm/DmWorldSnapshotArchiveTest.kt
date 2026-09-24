package calebxzau.rdi.mc.client.dm

import com.google.gson.JsonParser
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmWorldSnapshotArchiveTest {
    @Test
    fun writesV5MetadataAndOnlyChangedMembers(): Unit {
        val root = Files.createTempDirectory("dm-world-archive-")
        try {
            val staging = root.resolve("staging")
            val world = staging.resolve("world")
            val terrain = staging.resolve("chunks/dimensions/minecraft/overworld/terrain/0.0.nbt")
            Files.createDirectories(world)
            Files.createDirectories(terrain.parent)
            Files.writeString(world.resolve("level.dat"), "level")
            Files.writeString(world.resolve("unchanged.dat"), "unchanged")
            Files.writeString(terrain, "terrain")

            val column = DmSnapshotArchive.Column("minecraft:overworld", 0, 0)
            val record = DmSnapshotBatchWriter.Record(
                column,
                "dimensions/minecraft/overworld/terrain/0.0.nbt",
                null,
                null,
                "memory",
            )
            val request = DmWorldSnapshotArchive.Request(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                null,
                0,
                "2026-09-22T00:00:00Z",
                DmWorldSyncPolicy.EMPTY,
                listOf(
                    DmWorldFileEntry("level.dat", 5, "a".repeat(40)),
                    DmWorldFileEntry("unchanged.dat", 9, "b".repeat(40)),
                ),
                listOf("empty"),
                listOf(DmWorldColumnEntry(column, false, false, "c".repeat(40))),
                DmWorldSyncPlan(
                    changedFiles = listOf("level.dat"),
                    removedFiles = emptyList(),
                    updatedColumns = listOf(column),
                    inheritedColumns = emptyList(),
                    hasChanges = true,
                ),
            )
            val output = DmWorldSnapshotArchive.write(
                staging,
                root.resolve("upload.zip"),
                request,
                listOf(record),
            ) { false }
            val names = ZipInputStream(Files.newInputStream(output.archive)).use { zip ->
                buildList {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        add(entry.name)
                        zip.readBytes()
                        zip.closeEntry()
                    }
                }
            }
            assertEquals(
                setOf(
                    "metadata.json",
                    "world/level.dat",
                    "chunks/dimensions/minecraft/overworld/terrain/0.0.nbt",
                ),
                names.toSet(),
            )
            val metadata = JsonParser.parseString(Files.readString(staging.resolve("metadata.json"))).asJsonObject
            assertEquals(5, metadata.get("formatVersion").asInt)
            assertEquals("world-snapshot", metadata.get("kind").asString)
            assertEquals(false, metadata.get("restoreSafe").asBoolean)
            assertTrue(DmWorldSnapshotArchive.sha1(output.archive) { false }.matches(Regex("[0-9a-f]{40}")))
        } finally {
            DmWorldInitArchive.deleteRecursively(root).getOrThrow()
        }
    }
}
