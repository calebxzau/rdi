package calebxzau.rdi.mc.zstdcodec

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Keeps the capture directory to a fixed number of part files across every run, deleting the oldest first.
 *
 * Part names start with the run's UUIDv7, whose lowercase text sorts by start time, followed by the part
 * number, so (run name, part number) orders parts from oldest to newest. Stale `.partial` parts of runs
 * that ended abruptly count and age like finished ones.
 */
internal object PacketCaptureRetention {
    private val PART_NAME = Regex("""^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-(\d{6,})\.rdibatch\.zst(\.partial)?$""")

    private class Part(val path: Path, val run: String, val number: Long)

    /**
     * Deletes the oldest parts until one more part fits within [maxFiles], then the metadata of every
     * pruned run other than [activeRun] that no longer has parts. Returns the number of parts deleted.
     */
    fun makeRoomForPart(directory: Path, maxFiles: Int, activeRun: UUID): Result<Int> = runCatching {
        require(maxFiles >= 1) { "Packet capture needs room for at least one part" }
        if (!Files.isDirectory(directory)) return@runCatching 0
        val parts = listParts(directory)
        val excess = parts.size + 1 - maxFiles
        if (excess <= 0) return@runCatching 0
        val pruned = parts.take(excess)
        pruned.forEach { Files.deleteIfExists(it.path) }
        val remainingRuns = parts.drop(excess).mapTo(HashSet()) { it.run }
        pruned.mapTo(HashSet()) { it.run }
            .filter { it !in remainingRuns && it != activeRun.toString() }
            .forEach { Files.deleteIfExists(directory.resolve("${it}.json")) }
        excess
    }

    private fun listParts(directory: Path): List<Part> = Files.list(directory).use { paths ->
        paths.toList().mapNotNull { path ->
            val match = PART_NAME.matchEntire(path.fileName.toString()) ?: return@mapNotNull null
            val number = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            Part(path, match.groupValues[1], number)
        }
    }.sortedWith(compareBy<Part>({ it.run }, { it.number }))
}
