package calebxzau.rdi.bgrenderer

internal const val SCENE_VERTEX_FLOATS = 13

internal data class SceneMaterialBatch(
    val material: BlockMaterial,
    val firstIndex: Int,
    val indexCount: Int
)

internal data class SceneMesh(
    val vertices: FloatArray,
    val indices: IntArray,
    val batches: List<SceneMaterialBatch>,
    /** World bounds framed by the static shadow map; geometry outside it is treated as lit. */
    val shadowBounds: StaticAabb
)

/** A primitive float list; the scene mesh holds several hundred thousand vertices, so boxing is avoided. */
internal class FloatList(initialCapacity: Int = 1024) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Float = data[index]

    fun copyInto(destination: FloatArray, destinationOffset: Int) {
        data.copyInto(destination, destinationOffset, 0, size)
    }
}

internal class IntList(initialCapacity: Int = 1024) {
    private var data = IntArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]
}

/** Collects indexed scene geometry into one contiguous index range per [BlockMaterial]. */
internal class SceneMeshBuilder(colorOverrides: Map<BlockMaterial, SkyColor> = emptyMap()) {
    private val values = Array(BlockMaterial.entries.size) { FloatList() }
    private val indexValues = Array(BlockMaterial.entries.size) { IntList() }
    private val linearColors = BlockMaterial.entries.map { colorOverrides[it]?.toLinear() ?: it.linearColor() }

    /**
     * Emits one quad as four shared-corner vertices and six indices. Corners must be
     * counter-clockwise when viewed from the side the normal points to. [flipDiagonal]
     * splits along the 1-3 diagonal, which keeps corner AO interpolation symmetric.
     * [uvs] (u, v per corner) replaces the world-projected block UVs, and [kinds] gives
     * each corner its own vertex kind (used by swaying foliage tops).
     */
    fun quad(
        material: BlockMaterial,
        points: FloatArray,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
        kind: Int,
        ao: FloatArray = FULL_LIGHT,
        tint: FloatArray = FULL_LIGHT,
        flipDiagonal: Boolean = false,
        uvs: FloatArray? = null,
        kinds: IntArray? = null
    ) {
        val bucket = values[material.ordinal]
        val baseVertex = bucket.size / SCENE_VERTEX_FLOATS
        repeat(4) { corner ->
            val point = corner * 3
            appendVertex(
                bucket, material, points[point], points[point + 1], points[point + 2],
                normalX, normalY, normalZ, kinds?.get(corner) ?: kind, ao[corner], tint[corner],
                uvs?.get(corner * 2), uvs?.get(corner * 2 + 1)
            )
        }
        val order = if (flipDiagonal) FLIPPED_QUAD_ORDER else QUAD_ORDER
        order.forEach { index -> indexValues[material.ordinal].add(baseVertex + index) }
    }

    /** Legacy expanded quad (six vertices); used by the single water plane. */
    fun expandedQuad(material: BlockMaterial, points: FloatArray, normalX: Float, normalY: Float, normalZ: Float, kind: Int) {
        val bucket = values[material.ordinal]
        val baseVertex = bucket.size / SCENE_VERTEX_FLOATS
        QUAD_ORDER.forEachIndexed { emittedIndex, index ->
            val point = index * 3
            appendVertex(bucket, material, points[point], points[point + 1], points[point + 2], normalX, normalY, normalZ, kind, 1f, 1f)
            indexValues[material.ordinal].add(baseVertex + emittedIndex)
        }
    }

    fun vertexCount(material: BlockMaterial): Int = values[material.ordinal].size / SCENE_VERTEX_FLOATS

    /** Emits the faces of an axis-aligned box with the shared static-face winding; [skip] omits directions. */
    fun box(
        material: BlockMaterial,
        box: StaticAabb,
        kind: Int = 0,
        tint: Float = 1f,
        skip: Set<StaticFaceDirection> = emptySet(),
        topMaterial: BlockMaterial = material
    ) {
        val tints = floatArrayOf(tint, tint, tint, tint)
        StaticFaceDirection.entries.forEach { direction ->
            if (direction in skip) return@forEach
            quad(
                if (direction == StaticFaceDirection.PositiveY) topMaterial else material,
                direction.corners(box, direction.rect(box)),
                direction.normalX,
                direction.normalY,
                direction.normalZ,
                kind,
                tint = tints
            )
        }
    }

    fun build(shadowBounds: StaticAabb): SceneMesh {
        val vertices = FloatArray(values.sumOf { it.size })
        val indices = IntArray(indexValues.sumOf { it.size })
        val batches = ArrayList<SceneMaterialBatch>(BlockMaterial.entries.size)
        var vertexOffset = 0
        var indexOffset = 0
        values.forEachIndexed { index, bucket ->
            bucket.copyInto(vertices, vertexOffset)
            val localIndices = indexValues[index]
            val baseVertex = vertexOffset / SCENE_VERTEX_FLOATS
            for (localIndex in 0 until localIndices.size) {
                indices[indexOffset + localIndex] = localIndices[localIndex] + baseVertex
            }
            batches += SceneMaterialBatch(BlockMaterial.entries[index], indexOffset, localIndices.size)
            vertexOffset += bucket.size
            indexOffset += localIndices.size
        }
        return SceneMesh(vertices, indices, batches, shadowBounds)
    }

    private fun appendVertex(
        bucket: FloatList,
        material: BlockMaterial,
        x: Float,
        y: Float,
        z: Float,
        normalX: Float,
        normalY: Float,
        normalZ: Float,
        kind: Int,
        ao: Float,
        tint: Float,
        u: Float? = null,
        v: Float? = null
    ) {
        val color = linearColors[material.ordinal]
        bucket.add(x)
        bucket.add(y)
        bucket.add(z)
        bucket.add(normalX)
        bucket.add(normalY)
        bucket.add(normalZ)
        bucket.add(color.red * tint)
        bucket.add(color.green * tint)
        bucket.add(color.blue * tint)
        bucket.add(kind.toFloat())
        if (u != null && v != null) {
            bucket.add(u)
            bucket.add(v)
        } else {
            val uv = faceUv(x, y, z, normalX, normalY, normalZ)
            bucket.add(uv[0])
            bucket.add(uv[1])
        }
        bucket.add(ao)
    }

    private companion object {
        val FULL_LIGHT = floatArrayOf(1f, 1f, 1f, 1f)
        val QUAD_ORDER = intArrayOf(0, 1, 2, 0, 2, 3)
        val FLIPPED_QUAD_ORDER = intArrayOf(1, 2, 3, 1, 3, 0)
    }
}

/**
 * World-space block UVs: one texture repeat per block. Side faces use `-y` so the
 * image's top row (the grass fringe) sits at the top of each block.
 */
internal fun faceUv(x: Float, y: Float, z: Float, normalX: Float, normalY: Float, normalZ: Float): FloatArray =
    when {
        kotlin.math.abs(normalY) > 0.5f -> floatArrayOf(x, z)
        kotlin.math.abs(normalX) > 0.5f -> floatArrayOf(z, -y)
        kotlin.math.abs(normalZ) > 0.5f -> floatArrayOf(x, -y)
        else -> floatArrayOf(x, z)
    }
