package calebxzau.rdi.anvilrw.remap

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.UUID

/**
 * Metadata from `level.dat` used by the import prechecks. Getters follow Minecraft's lenient
 * `CompoundTag` getters: a missing field or a field of the wrong type reads as absent/false.
 */
data class LevelMetadata(
    /** `Data.Version.Name`, e.g. `1.20.1`. */
    val versionName: String?,
    /** `Data.DataVersion`. */
    val dataVersion: Int?,
    /** `Data.ServerBrands`, a cumulative history (see plan §3.4). */
    val serverBrands: List<String>,
    /** `Data.hardcore`. */
    val hardcore: Boolean,
    /** Absolute offset of the `Data.hardcore` byte, for switching hardcore off in place. */
    val hardcoreByteOffset: Int?,
    /** `Data.Player.UUID`, the singleplayer owner. */
    val singleplayerUuid: UUID?,
    /** `ModId`s of the root `fml.LoadingModList`, or null when the root has no `fml` compound. */
    val fmlModIds: List<String>?,
)

/**
 * Read-only access to a few values of an NBT document, by path.
 *
 * The whole document is validated by [NbtWalker] first, so lookups only ever follow structure that
 * is known to be in bounds. Nothing is allocated from a declared length, and only requested strings
 * are decoded (with Java modified UTF-8 semantics). This replaces knbt for untrusted input.
 */
class NbtMetadataReader private constructor(
    private val src: ByteArray,
    rootPayloadOffset: Int,
) {
    val root: NbtNode = NbtNode(src, NbtTag.COMPOUND, rootPayloadOffset)

    /** The exact payload bytes of the compound at [path], or null if it is absent or not a compound. */
    fun compoundPayload(vararg path: String): ByteArray? {
        var node: NbtNode? = root
        for (name in path) node = node?.child(name)
        val compound = node?.takeIf { it.type == NbtTag.COMPOUND } ?: return null
        return src.copyOfRange(compound.payloadOffset, compound.payloadEnd())
    }

    /** Replaces one named compound without decoding/re-encoding unrelated or mod-owned tags. */
    fun replaceCompound(parentPath: List<String>, name: String, payload: ByteArray): Result<ByteArray> = remapResult {
        open(rootDocument(payload)).getOrThrow()
        var parent = root
        for (part in parentPath) parent = requireNotNull(parent.uniqueChild(part)) { "Missing ${part}" }
        require(parent.type == NbtTag.COMPOUND) { "Parent is not a compound" }
        val header = java.io.ByteArrayOutputStream().also { buffer ->
            java.io.DataOutputStream(buffer).use { it.writeByte(NbtTag.COMPOUND); it.writeUTF(name) }
        }.toByteArray()
        val old = parent.uniqueChild(name)
        require(old == null || old.type == NbtTag.COMPOUND) { "${name} is not a compound" }
        val start = old?.let { it.payloadOffset - header.size } ?: (parent.payloadEnd() - 1)
        val end = old?.payloadEnd() ?: start
        val size = src.size.toLong() - (end - start) + header.size + payload.size
        require(size <= RemapLimits.STRICT_NBT_FILE_BYTES) { "Edited NBT exceeds size limit" }
        val result = src.copyOfRange(0, start) + header + payload + src.copyOfRange(end, src.size)
        open(result).getOrThrow()
        result
    }

    /**
     * Reads the `level.dat` fields listed in [LevelMetadata]. Fails only if a requested string is not
     * valid modified UTF-8.
     */
    fun levelMetadata(): Result<LevelMetadata> = remapResult {
        val data = root.child("Data")?.takeIf { it.type == NbtTag.COMPOUND }
        val hardcore = data?.child("hardcore")?.takeIf { it.type == NbtTag.BYTE }
        val fml = root.child("fml")?.takeIf { it.type == NbtTag.COMPOUND }
        LevelMetadata(
            versionName = data?.child("Version")?.child("Name")?.stringOrNull(),
            dataVersion = data?.child("DataVersion")?.intOrNull(),
            serverBrands = data?.child("ServerBrands")?.listElements()?.mapNotNull { it.stringOrNull() } ?: emptyList(),
            hardcore = hardcore?.byteOrNull()?.let { it != 0 } ?: false,
            hardcoreByteOffset = hardcore?.payloadOffset,
            singleplayerUuid = data?.child("Player")?.child("UUID")?.uuidOrNull(),
            fmlModIds = fml?.let { compound ->
                compound.child("LoadingModList")?.listElements()
                    ?.mapNotNull { it.child("ModId")?.stringOrNull() }
                    ?: emptyList()
            },
        )
    }

    companion object {
        /** Validates [nbt] as a complete document and opens it for lookups. */
        fun open(nbt: ByteArray): Result<NbtMetadataReader> = remapResult {
            val rootPayload = NbtWalker(nbt).walkDocument(null)
            NbtMetadataReader(nbt, rootPayload)
        }

        /**
         * Reads the [LevelMetadata] of a gzip `level.dat` file from untrusted input: at most
         * [RemapLimits.STRICT_NBT_FILE_BYTES] compressed and decompressed, links are not followed.
         */
        fun readLevelDat(file: java.nio.file.Path): Result<LevelMetadata> = remapResult {
            openGzipFile(file).getOrThrow().levelMetadata().getOrThrow()
        }

        /**
         * Opens a gzip NBT file from untrusted input, at most [RemapLimits.STRICT_NBT_FILE_BYTES]
         * compressed and decompressed, without following links.
         */
        fun openGzipFile(file: java.nio.file.Path): Result<NbtMetadataReader> = remapResult {
            val attributes = java.nio.file.Files.readAttributes(
                file, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isRegularFile) throw java.io.IOException("${file} is not a regular file")
            if (attributes.size() > RemapLimits.STRICT_NBT_FILE_BYTES) {
                throw RemapLimitExceededException("${file.fileName} exceeds ${RemapLimits.STRICT_NBT_FILE_BYTES} bytes")
            }
            val compressed = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS).use {
                BoundedIo.readAtMost(java.nio.channels.Channels.newInputStream(it), RemapLimits.STRICT_NBT_FILE_BYTES, file.fileName.toString())
            }
            val nbt = BoundedIo.gunzip(compressed, RemapLimits.STRICT_NBT_FILE_BYTES, file.fileName.toString())
            open(nbt).getOrThrow()
        }

        /** Wraps a compound payload as a document with an unnamed root, as `NbtIo.write` does. */
        fun rootDocument(compoundPayload: ByteArray): ByteArray =
            byteArrayOf(NbtTag.COMPOUND.toByte(), 0, 0) + compoundPayload
    }
}

/** A tag inside a document opened by [NbtMetadataReader]. */
class NbtNode internal constructor(
    private val src: ByteArray,
    val type: Int,
    val payloadOffset: Int,
) {
    /** Identity edits must not disagree with Minecraft about duplicate named entries. */
    fun uniqueChild(name: String): NbtNode? {
        if (type != NbtTag.COMPOUND) return null
        val wanted = encodeModifiedUtf8(name)
        val walker = NbtWalker(src)
        var position = payloadOffset
        var found: NbtNode? = null
        while (true) {
            val entryType = src[position].toInt() and 0xff
            if (entryType == NbtTag.END) return found
            val nameLength = NbtBytes.u2(src, position + 1)
            val nameOffset = position + 3
            val payload = nameOffset + nameLength
            if (nameLength == wanted.size && regionEquals(nameOffset, wanted)) {
                require(found == null) { "Duplicate NBT entry: ${name}" }
                found = NbtNode(src, entryType, payload)
            }
            position = walker.walkPayload(entryType, payload, 0, null)
        }
    }

    /** The entry named [name] of this compound, or null. */
    fun child(name: String): NbtNode? {
        if (type != NbtTag.COMPOUND) return null
        val wanted = encodeModifiedUtf8(name)
        val walker = NbtWalker(src)
        var position = payloadOffset
        while (true) {
            val entryType = src[position].toInt() and 0xff
            if (entryType == NbtTag.END) return null
            val nameLength = NbtBytes.u2(src, position + 1)
            val nameOffset = position + 3
            val payload = nameOffset + nameLength
            if (nameLength == wanted.size && regionEquals(nameOffset, wanted)) {
                return NbtNode(src, entryType, payload)
            }
            position = walker.walkPayload(entryType, payload, 0, null)
        }
    }

    /** The elements of this list, or null if this is not a list. */
    fun listElements(): List<NbtNode>? {
        if (type != NbtTag.LIST) return null
        val elementType = src[payloadOffset].toInt() and 0xff
        val count = NbtBytes.s4(src, payloadOffset + 1)
        val walker = NbtWalker(src)
        var position = payloadOffset + 5
        val elements = ArrayList<NbtNode>(minOf(count, 1024))
        repeat(count) {
            elements += NbtNode(src, elementType, position)
            position = walker.walkPayload(elementType, position, 0, null)
        }
        return elements
    }

    fun payloadEnd(): Int = NbtWalker(src).walkPayload(type, payloadOffset, 0, null)

    fun stringOrNull(): String? {
        if (type != NbtTag.STRING) return null
        val length = NbtBytes.u2(src, payloadOffset)
        return DataInputStream(ByteArrayInputStream(src, payloadOffset, 2 + length)).readUTF()
    }

    fun intOrNull(): Int? = if (type == NbtTag.INT) NbtBytes.s4(src, payloadOffset) else null

    fun byteOrNull(): Int? = if (type == NbtTag.BYTE) src[payloadOffset].toInt() else null

    fun doubleOrNull(): Double? =
        if (type == NbtTag.DOUBLE) java.lang.Double.longBitsToDouble(NbtBytes.s8(src, payloadOffset)) else null

    /** A UUID stored as `IntArray[4]`, the way Minecraft stores entity UUIDs. */
    fun uuidOrNull(): UUID? {
        if (type != NbtTag.INT_ARRAY || NbtBytes.s4(src, payloadOffset) != 4) return null
        return UUID(NbtBytes.s8(src, payloadOffset + 4), NbtBytes.s8(src, payloadOffset + 12))
    }

    private fun regionEquals(offset: Int, bytes: ByteArray): Boolean {
        for (i in bytes.indices) {
            if (src[offset + i] != bytes[i]) return false
        }
        return true
    }

    private fun encodeModifiedUtf8(value: String): ByteArray {
        val output = java.io.ByteArrayOutputStream(value.length + 2)
        java.io.DataOutputStream(output).writeUTF(value)
        return output.toByteArray().copyOfRange(2, output.size())
    }
}
