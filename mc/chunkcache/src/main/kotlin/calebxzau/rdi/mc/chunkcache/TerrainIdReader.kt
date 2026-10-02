package calebxzau.rdi.mc.chunkcache

/** Reads a semantic terrain registry ID without boxing its coordinates or result. */
fun interface TerrainIdReader {
    fun get(section: Int, x: Int, y: Int, z: Int): Int
}
