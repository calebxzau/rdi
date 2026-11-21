package calebxzau.rdi.service

/** Keep complete trailing lines within the framed protocol's byte budget. */
internal fun boundedConsoleLines(lines: List<String>, byteLimit: Int = 1_000_000): List<String> {
    var remaining = byteLimit
    val result = ArrayDeque<String>()
    for (line in lines.asReversed()) {
        val bounded = line.take(4096)
        // JSON escaping can use six bytes for one UTF-16 character.
        val bytes = bounded.length * 6 + 8
        if (bytes > remaining) break
        remaining -= bytes
        result.addFirst(bounded)
        if (result.size >= 5000) break
    }
    return result.toList()
}
