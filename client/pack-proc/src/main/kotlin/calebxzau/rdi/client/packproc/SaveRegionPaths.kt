package calebxzau.rdi.client.packproc

/**
 * Finds the region files (`.mca`/`.mcc`) of saves embedded in a modpack, which are uploaded as empty
 * placeholders; built-in maps are made into base world templates instead. A save is a directory
 * holding `level.dat`, so region files that mods ship as dimension templates elsewhere are kept.
 * Anything under an `ftbteambases` directory is always kept.
 */
internal class SaveRegionPaths private constructor(private val saveRoots: Set<String>) {
    fun isSaveRegion(path: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        val normalized = normalize(path)
        val name = normalized.substringAfterLast('/')
        if (!name.endsWith(".mca", ignoreCase = true) && !name.endsWith(".mcc", ignoreCase = true)) return false
        if (normalized.split('/').any { it.equals("ftbteambases", ignoreCase = true) }) return false
        val lower = normalized.lowercase()
        return saveRoots.any { root -> root.isEmpty() || lower.startsWith("${root}/") }
    }

    companion object {
        val NONE = SaveRegionPaths(emptySet())

        /** [paths] are the files of one tree, relative to its root; the `level.dat` among them mark the saves. */
        fun of(paths: Iterable<String>): SaveRegionPaths = SaveRegionPaths(
            paths.asSequence()
                .map(::normalize)
                .filter { it.substringAfterLast('/').equals("level.dat", ignoreCase = true) }
                .map { it.substringBeforeLast('/', "").lowercase() }
                .toSet()
        )

        private fun normalize(path: String) = path.replace('\\', '/').trim('/')
    }
}
