@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package calebxzau.rdi.mc.client.chunkcache

import calebxzau.rdi.mc.client.chunkcache.ClientChunkCacheWrite.BlockChange
import calebxzau.rdi.mc.client.chunkcache.ClientChunkCacheWrite.BlockEntityChange
import calebxzau.rdi.mc.client.chunkcache.ClientChunkCacheWrite.Biomes as BiomeDelta
import calebxzau.rdi.mc.client.chunkcache.ClientChunkCacheWrite.Update
import calebxzau.rdi.mc.client.chunkcache.ClientChunkSnapshot.Snapshot
import calebxzau.rdi.mc.client.chunkcache.ClientChunkRegionSink.Key
import calebxzau.rdi.mc.client.chunkcache.ClientChunkBinaryCodec
import calebxzau.rdi.mc.regioncodec.RegionZstdStreams
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import io.netty.buffer.Unpooled
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.storage.RegionFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.UUID

/** Worker-owned incremental cache. It never retains a live LevelChunk. */
class ClientChunkDeltaStore(root: Path) : AutoCloseable {
    data class BaseGeneration(val id: UUID, val sequence: Long, val minSection: Int, val sectionCount: Int)
    sealed interface ReplayEvent {
        val position: Long
        data class Block(override val position: Long, val stateId: Int, val hasBlockEntity: Boolean) : ReplayEvent
        data class BlockEntity(override val position: Long, val type: String, val tag: CompoundTag) : ReplayEvent
    }
    data class BlockStateDelta(val stateId: Int, val hasBlockEntity: Boolean)
    data class Combined(
        val base: Snapshot,
        val blockStates: Map<Long, BlockStateDelta>,
        val replay: List<ReplayEvent>,
        val biomeData: ByteArray?,
        val heightmapsStale: Boolean,
    ) {
        /** Decodes with the caller's live biome registry, then applies detached deltas. */
        @Throws(IOException::class)
        fun decodeSections(biomes: Registry<Biome>): Array<LevelChunkSection> =
            decodeTerrainSections(base, blockStates, biomeData, biomes)
    }
    data class Terrain(
        val base: Snapshot,
        val blockStates: Map<Long, BlockStateDelta>,
        val biomeData: ByteArray?,
    ) {
        /** Decodes only terrain state; block-entity replay stays on the full-read path. */
        @Throws(IOException::class)
        fun decodeSections(biomes: Registry<Biome>): Array<LevelChunkSection> =
            decodeTerrainSections(base, blockStates, biomeData, biomes)
    }

    private data class BeEntry(val sequence: Long, val order: Int, val replay: ReplayEvent)
    private data class BlockValue(val stateId: Int, val hasBlockEntity: Boolean, val sequence: Long, val order: Int)
    private class Overlay(val generation: BaseGeneration, var lastSequence: Long) {
        val blocks = LinkedHashMap<Long, BlockValue>()
        val blockEntityHistory = LinkedHashMap<Long, MutableList<BeEntry>>()
        val trackedBlockEntities = HashSet<Long>()
        var biomes: ByteArray? = null
        var biomeSequence = 0L
        var dirty = false
        var estimatedBytes = 128L
        var eventCount = 0
    }

    private val worldRoot = root.toAbsolutePath().normalize()
    private val sessionId = UUID.fromString(kotlin.uuid.Uuid.generateV7().toString())
    private val baseSink = ClientChunkRegionSink(worldRoot)
    private val baseGenerations = LinkedHashMap<Key, BaseGeneration>(16, .75f, true)
    private val overlays = LinkedHashMap<Key, Overlay>(16, .75f, true)
    private val deltaRegions = LinkedHashMap<Path, RegionFile>(16, .75f, true)
    private var closed = false
    private var failed = false

    /** Called only by the serial cache writer, in the sequence assigned on the client thread. */
    @Synchronized
    @Throws(IOException::class)
    fun accept(write: ClientChunkCacheWrite) {
        ensureOpen()
        try {
            acceptInternal(write)
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
    }

    private fun acceptInternal(write: ClientChunkCacheWrite) {
        val key = write.key()
        if (write.base() != null) {
            val snapshot = write.base()
            validateKey(key, snapshot)
            val prior = baseGenerations[key]?.let { it.id to it.sequence }
                ?: baseSink.readEncoded(key)?.let(::peekBaseGeneration)
            if (prior?.first == sessionId && write.sequence() <= prior.second) {
                throw IOException("Nonmonotonic chunk base sequence")
            }
            val generation = BaseGeneration(sessionId,
                write.sequence(), snapshot.minSection(), snapshot.sectionCount())
            val bytes = encodeBase(generation, snapshot)
            baseSink.writeEncoded(key, bytes)
            baseGenerations[key] = generation
            overlays.remove(key)
            val overlay = Overlay(generation, write.sequence())
            snapshot.blockEntities().forEach { entity ->
                overlay.trackedBlockEntities.add(BlockPos.asLong(entity.x(), entity.y(), entity.z()))
            }
            overlay.estimatedBytes = estimateOverlay(overlay)
            overlays[key] = overlay
            enforceGenerationLimit()
            enforceOverlayLimits()
            return
        }
        val generation = baseGenerations[key] ?: run {
            val encoded = baseSink.readEncoded(key) ?: return
            val header = peekBaseGeneration(encoded) ?: return
            if (header.first != sessionId) return // Never attach this session's deltas to an old base.
            val (diskGeneration, snapshot) = decodeBase(encoded, key)
            if (header != (diskGeneration.id to diskGeneration.sequence)) throw IOException("Inconsistent base generation header")
            baseGenerations[key] = diskGeneration
            enforceGenerationLimit()
            diskGeneration
        }
        if (write.sequence() <= generation.sequence) throw IOException("Delta sequence precedes base generation")
        val overlay = loadOverlay(key, generation) ?: newOverlay(key, generation).also { overlays[key] = it }
        if (write.sequence() <= overlay.lastSequence) throw IOException("Nonmonotonic chunk delta sequence")
        val encodedSize = write.estimatedBytes()
        if (encodedSize <= 0 || encodedSize > MAX_OVERLAY_BYTES) throw IOException("Chunk delta exceeds limit")
        if (write.updates().size > MAX_BLOCK_CHANGES + MAX_BE_EVENTS + 1) throw IOException("Too many chunk delta updates")
        var order = 0
        for (update in write.updates()) {
            val updateOrder = order++
            when (update) {
                is BlockChange -> {
                    validatePosition(key, update.position())
                    val blockPosition = BlockPos.of(update.position())
                    if ((blockPosition.y shr 4) !in generation.minSection until generation.minSection + generation.sectionCount) {
                        throw IOException("Delta block is outside base section range")
                    }
                    if (update.stateId() < 0 || Block.BLOCK_STATE_REGISTRY.byId(update.stateId()) == null) {
                        throw IOException("Unknown block state id")
                    }
                    val prior = overlay.blocks[update.position()]
                    if (prior == null) overlay.estimatedBytes += BLOCK_ENTRY_BYTES
                    overlay.blocks[update.position()] = BlockValue(update.stateId(), update.hasBlockEntity(), write.sequence(), updateOrder)
                    if (update.position() in overlay.trackedBlockEntities || update.hasBlockEntity()) {
                        if (update.position() !in overlay.trackedBlockEntities && prior != null) {
                            appendEvent(overlay, update.position(), BeEntry(
                                prior.sequence, prior.order, ReplayEvent.Block(update.position(), prior.stateId, prior.hasBlockEntity)))
                        }
                        trackPosition(overlay, update.position())
                        appendEvent(overlay, update.position(), BeEntry(write.sequence(), updateOrder,
                            ReplayEvent.Block(update.position(), update.stateId(), update.hasBlockEntity())))
                    }
                }
                is BlockEntityChange -> {
                    validatePosition(key, update.position())
                    val type = update.type()
                    if (type == null || type.length > MAX_NAME_BYTES || BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                            net.minecraft.resources.ResourceLocation.tryParse(type)) == null) {
                        throw IOException("Invalid block entity type")
                    }
                    // 1.20.1 encodes an empty block-entity update tag as null. There is no
                    // NBT to replay; keep prior data and continue the rest of this ordered batch.
                    val tag = update.tag()?.copy() ?: continue
                    checkTag(tag)
                    if (update.position() !in overlay.trackedBlockEntities) {
                        overlay.blocks[update.position()]?.let { prior ->
                            appendEvent(overlay, update.position(), BeEntry(
                                prior.sequence, prior.order, ReplayEvent.Block(update.position(), prior.stateId, prior.hasBlockEntity)))
                        }
                    }
                    trackPosition(overlay, update.position())
                    appendEvent(overlay, update.position(), BeEntry(write.sequence(), updateOrder, ReplayEvent.BlockEntity(update.position(), type, tag)))
                }
                is BiomeDelta -> {
                    val data = update.data().clone()
                    if (data.size > MAX_BIOME_BYTES) throw IOException("Biome delta exceeds limit")
                    overlay.estimatedBytes += data.size - (overlay.biomes?.size ?: 0)
                    overlay.biomes = data
                    overlay.biomeSequence = write.sequence()
                }
                else -> throw IOException("Unknown chunk update")
            }
        }
        overlay.lastSequence = write.sequence()
        overlay.dirty = true
        if (overlay.blocks.size > MAX_BLOCK_CHANGES || overlay.eventCount > MAX_BE_EVENTS ||
            overlay.trackedBlockEntities.size > MAX_BE_EVENTS) throw IOException("Chunk delta event count exceeds limit")
        if (overlay.estimatedBytes > MAX_OVERLAY_BYTES) throw IOException("Chunk delta overlay exceeds limit")
        overlays[key] = overlay
        enforceOverlayLimits()
    }

    @Synchronized
    @Throws(IOException::class)
    fun flush() {
        ensureOpen()
        if (failed) throw IOException("Chunk delta store is failed")
        try {
            for ((key, overlay) in overlays) if (overlay.dirty) persist(key, overlay)
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
    }

    /** Loads only the matching generation and returns detached section data plus ordered BE replay. */
    @Synchronized
    @Throws(IOException::class)
    fun read(key: Key): Combined? {
        val (snapshot, overlay) = readBaseAndOverlay(key) ?: return null
        val replay = overlay?.blockEntityHistory?.values?.flatten()
            ?.sortedWith(compareBy<BeEntry> { it.sequence }.thenBy { it.order })?.map { entry ->
                when (val event = entry.replay) {
                    is ReplayEvent.Block -> event
                    is ReplayEvent.BlockEntity -> event.copy(tag = event.tag.copy())
                }
            } ?: emptyList()
        val blocks = overlay?.blocks?.mapValues { (_, value) -> BlockStateDelta(value.stateId, value.hasBlockEntity) } ?: emptyMap()
        return Combined(snapshot, blocks, replay, overlay?.biomes?.clone(), blocks.isNotEmpty())
    }

    /** Reads detached terrain data without flattening or copying block-entity replay history. */
    @Synchronized
    @Throws(IOException::class)
    fun readTerrain(key: Key): Terrain? {
        val (snapshot, overlay) = readBaseAndOverlay(key) ?: return null
        val blocks = LinkedHashMap<Long, BlockStateDelta>(overlay?.blocks?.size ?: 0)
        overlay?.blocks?.forEach { (position, value) ->
            blocks[position] = BlockStateDelta(value.stateId, value.hasBlockEntity)
        }
        return Terrain(snapshot, blocks, overlay?.biomes?.clone())
    }

    private fun readBaseAndOverlay(key: Key): Pair<Snapshot, Overlay?>? {
        ensureOpen()
        val raw = baseSink.readEncoded(key) ?: return null
        val (generation, snapshot) = decodeBase(raw, key)
        val overlay = overlays[key]?.takeIf { it.generation == generation } ?: readOverlay(key, generation)
        return snapshot to overlay
    }

    @Synchronized
    @Throws(IOException::class)
    override fun close() {
        if (closed) return
        var failure: IOException? = null
        if (!failed) try { flush() } catch (error: IOException) { failure = error }
        for (region in deltaRegions.values) try { region.close() } catch (error: IOException) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        deltaRegions.clear()
        try { baseSink.close() } catch (error: IOException) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        closed = true
        failure?.let { throw it }
    }

    private fun loadOverlay(key: Key, generation: BaseGeneration): Overlay? {
        overlays[key]?.takeIf { it.generation == generation }?.let { return it }
        return readOverlay(key, generation)?.also { overlays[key] = it }
    }

    private fun enforceGenerationLimit() {
        while (baseGenerations.size > MAX_GENERATIONS) {
            val iterator = baseGenerations.entries.iterator()
            iterator.next(); iterator.remove()
        }
    }

    private fun newOverlay(key: Key, generation: BaseGeneration, snapshot: Snapshot? = null): Overlay {
        val result = Overlay(generation, generation.sequence)
        val base = snapshot ?: baseSink.readEncoded(key)?.let { decodeBase(it, key).second }
        base?.blockEntities()?.forEach { entity ->
            result.trackedBlockEntities.add(BlockPos.asLong(entity.x(), entity.y(), entity.z()))
        }
        result.estimatedBytes = estimateOverlay(result)
        return result
    }

    private fun enforceOverlayLimits() {
        while (overlays.size > MAX_OVERLAYS || overlays.values.sumOf { it.estimatedBytes } > MAX_RETAINED_BYTES) {
            val iterator = overlays.entries.iterator()
            if (!iterator.hasNext()) return
            val (key, overlay) = iterator.next()
            if (overlay.dirty) persist(key, overlay)
            iterator.remove()
        }
    }

    private fun persist(key: Key, overlay: Overlay) {
        val bytes = encodeOverlay(overlay)
        (deltaRegion(key) ?: throw IOException("Unable to open delta region")).getChunkDataOutputStream(ChunkPos(key.x(), key.z())).use { output ->
            try { output.write(bytes) } catch (failure: Throwable) { RegionZstdStreams.abort(output); throw failure }
        }
        overlay.dirty = false
    }

    private fun readOverlay(key: Key, generation: BaseGeneration): Overlay? {
        val folder = deltaDirectory(key)
        val path = folder.resolve("r.${key.x() shr 5}.${key.z() shr 5}.mca")
        if (!Files.isRegularFile(path)) return null
        val region = deltaRegion(key, create = false) ?: return null
        val input = region.getChunkDataInputStream(ChunkPos(key.x(), key.z())) ?: return null
        val bytes = input.use { readBounded(it, MAX_OVERLAY_BYTES) }
        return decodeOverlay(bytes, generation, key)
    }

    private fun deltaRegion(key: Key, create: Boolean = true): RegionFile? {
        val folder = deltaDirectory(key)
        val path = folder.resolve("r.${key.x() shr 5}.${key.z() shr 5}.mca")
        deltaRegions[path]?.let { return it }
        if (!create && !Files.isRegularFile(path)) return null
        Files.createDirectories(folder)
        if (deltaRegions.size >= MAX_OPEN_REGIONS) {
            val iterator = deltaRegions.entries.iterator()
            val oldest = iterator.next(); oldest.value.close(); iterator.remove()
        }
        val dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, key.dimension())
        return ChunkCacheCompat.openRegion("rdi-client-chunk-deltas", dimension, path, folder)
            .also { deltaRegions[path] = it }
    }

    private fun deltaDirectory(key: Key): Path {
        val relative = worldRoot.relativize(baseSink.directory(key.dimension()))
        if (relative.nameCount < 3 || relative.getName(0).toString() != "dimensions") throw IOException("Invalid dimension directory")
        return worldRoot.resolve("deltas").resolve(relative.subpath(1, relative.nameCount))
    }

    private fun encodeBase(generation: BaseGeneration, snapshot: Snapshot): ByteArray {
        val record = ByteArrayOutputStream()
        ClientChunkBinaryCodec.write(DataOutputStream(record), snapshot)
        val payload = record.toByteArray()
        if (payload.size > MAX_RECORD_BYTES) throw IOException("Base chunk exceeds record limit")
        val output = ByteArrayOutputStream(payload.size + BASE_HEADER_BYTES)
        DataOutputStream(output).use { data ->
            data.writeInt(BASE_MAGIC); data.writeShort(FORMAT_VERSION)
            data.writeLong(generation.id.mostSignificantBits); data.writeLong(generation.id.leastSignificantBits)
            data.writeLong(generation.sequence); data.writeInt(payload.size); data.write(payload)
        }
        return output.toByteArray()
    }

    private fun decodeBase(bytes: ByteArray, key: Key): Pair<BaseGeneration, Snapshot> {
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != BASE_MAGIC || input.readUnsignedShort() != FORMAT_VERSION) throw IOException("Unsupported delta base record")
            val id = UUID(input.readLong(), input.readLong()); val sequence = input.readLong()
            val length = input.readInt()
            if (sequence <= 0 || length !in 1..MAX_RECORD_BYTES || length != input.available()) throw IOException("Invalid base record length")
            val payload = ByteArray(length); input.readFully(payload)
            val body = DataInputStream(ByteArrayInputStream(payload))
            val snapshot = ClientChunkBinaryCodec.read(body)
            if (body.available() != 0) throw IOException("Trailing base record bytes")
            validateKey(key, snapshot)
            return BaseGeneration(id, sequence, snapshot.minSection(), snapshot.sectionCount()) to snapshot
        } catch (failure: RuntimeException) { throw IOException("Malformed base record", failure) }
    }

    private fun peekBaseGeneration(bytes: ByteArray): Pair<UUID, Long>? {
        if (bytes.size < BASE_HEADER_BYTES) return null
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != BASE_MAGIC || input.readUnsignedShort() != FORMAT_VERSION) return null
        val id = UUID(input.readLong(), input.readLong())
        val sequence = input.readLong()
        return if (sequence > 0) id to sequence else null
    }

    private fun encodeOverlay(overlay: Overlay): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(DELTA_MAGIC); out.writeShort(FORMAT_VERSION)
            out.writeLong(overlay.generation.id.mostSignificantBits); out.writeLong(overlay.generation.id.leastSignificantBits)
            out.writeLong(overlay.generation.sequence); out.writeLong(overlay.lastSequence)
            out.writeInt(overlay.blocks.size)
            for ((position, value) in overlay.blocks) {
                out.writeLong(position); out.writeInt(value.stateId); out.writeBoolean(value.hasBlockEntity)
                out.writeLong(value.sequence); out.writeInt(value.order)
            }
            out.writeInt(overlay.trackedBlockEntities.size)
            for (position in overlay.trackedBlockEntities) out.writeLong(position)
            out.writeInt(overlay.blockEntityHistory.values.sumOf { it.size })
            for ((position, entries) in overlay.blockEntityHistory) for (entry in entries) {
                out.writeLong(position); out.writeLong(entry.sequence); out.writeInt(entry.order)
                when (val event = entry.replay) {
                    is ReplayEvent.Block -> { out.writeByte(0); out.writeInt(event.stateId); out.writeBoolean(event.hasBlockEntity) }
                    is ReplayEvent.BlockEntity -> { out.writeByte(1); writeString(out, event.type); NbtIo.write(event.tag, out) }
                }
            }
            out.writeLong(overlay.biomeSequence)
            val biomes = overlay.biomes; out.writeInt(biomes?.size ?: -1); if (biomes != null) out.write(biomes)
        }
        val bytes = buffer.toByteArray()
        if (bytes.size > MAX_OVERLAY_BYTES) throw IOException("Chunk delta overlay exceeds limit")
        return bytes
    }

    private fun decodeOverlay(bytes: ByteArray, baseGeneration: BaseGeneration, key: Key): Overlay? {
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != DELTA_MAGIC || input.readUnsignedShort() != FORMAT_VERSION) throw IOException("Unsupported chunk delta record")
            val id = UUID(input.readLong(), input.readLong())
            val baseSequence = input.readLong()
            if (id != baseGeneration.id || baseSequence != baseGeneration.sequence) return null
            val generation = BaseGeneration(id, baseSequence,
                baseGeneration.minSection, baseGeneration.sectionCount)
            val lastSequence = input.readLong()
            if (generation.sequence <= 0 || lastSequence < generation.sequence) throw IOException("Invalid delta sequence")
            val overlay = Overlay(generation, lastSequence)
            val blockCount = input.readInt(); if (blockCount !in 0..MAX_BLOCK_CHANGES) throw IOException("Invalid block delta count")
            repeat(blockCount) {
                val position = input.readLong(); val state = input.readInt(); val hasEntity = input.readBoolean()
                val sequence = input.readLong(); val order = input.readInt()
                if (sequence <= generation.sequence || sequence > lastSequence || order < 0) throw IOException("Invalid block delta sequence")
                validatePosition(key, position)
                val y = BlockPos.of(position).y shr 4
                if (y !in baseGeneration.minSection until baseGeneration.minSection + baseGeneration.sectionCount ||
                    state < 0 || Block.BLOCK_STATE_REGISTRY.byId(state) == null) throw IOException("Invalid block delta value")
                overlay.blocks[position] = BlockValue(state, hasEntity, sequence, order)
            }
            val trackedCount = input.readInt(); if (trackedCount !in 0..MAX_BE_EVENTS) throw IOException("Invalid tracked block entity count")
            repeat(trackedCount) {
                val position = input.readLong(); validatePosition(key, position); overlay.trackedBlockEntities.add(position)
            }
            val eventCount = input.readInt(); if (eventCount !in 0..MAX_BE_EVENTS) throw IOException("Invalid block entity event count")
            repeat(eventCount) {
                val position = input.readLong(); val sequence = input.readLong(); val order = input.readInt()
                if (sequence <= generation.sequence || sequence > lastSequence || order < 0) throw IOException("Invalid block entity event sequence")
                validatePosition(key, position)
                val event = when (input.readUnsignedByte()) {
                    0 -> {
                        val state = input.readInt(); val hasEntity = input.readBoolean()
                        if (state < 0 || Block.BLOCK_STATE_REGISTRY.byId(state) == null) throw IOException("Invalid block entity replay state")
                        ReplayEvent.Block(position, state, hasEntity)
                    }
                    1 -> {
                        val type = readString(input)
                        val id = net.minecraft.resources.ResourceLocation.tryParse(type)
                        if (id == null || BuiltInRegistries.BLOCK_ENTITY_TYPE.get(id) == null) throw IOException("Invalid block entity type")
                        val tag = NbtIo.read(input, ChunkCacheCompat.nbtAccounter(MAX_OVERLAY_BYTES.toLong(), 512))
                        ReplayEvent.BlockEntity(position, type, tag)
                    }
                    else -> throw IOException("Unknown block entity event")
                }
                overlay.blockEntityHistory.getOrPut(position) { mutableListOf() }.add(BeEntry(sequence, order, event))
                overlay.eventCount++
            }
            if (overlay.blockEntityHistory.keys.any { it !in overlay.trackedBlockEntities }) throw IOException("Untracked block entity replay position")
            overlay.biomeSequence = input.readLong()
            val biomeLength = input.readInt()
            if (biomeLength !in -1..MAX_BIOME_BYTES || (biomeLength >= 0 && (overlay.biomeSequence <= generation.sequence || overlay.biomeSequence > lastSequence))) throw IOException("Invalid biome delta length or sequence")
            if (biomeLength < 0 && overlay.biomeSequence != 0L) throw IOException("Biome sequence exists without biome data")
            if (biomeLength >= 0) overlay.biomes = ByteArray(biomeLength).also(input::readFully)
            if (input.available() != 0) throw IOException("Trailing delta record bytes")
            overlay.estimatedBytes = estimateOverlay(overlay)
            if (overlay.estimatedBytes > MAX_OVERLAY_BYTES) throw IOException("Decoded chunk overlay exceeds retained-data limit")
            return overlay
        } catch (failure: RuntimeException) { throw IOException("Malformed delta record", failure) }
    }

    private fun validateKey(key: Key, snapshot: Snapshot) {
        if (key.dimension() != snapshot.dimension() || key.x() != snapshot.x() || key.z() != snapshot.z()) throw IOException("Base chunk key mismatch")
    }

    private fun validatePosition(key: Key, packed: Long) {
        val pos = BlockPos.of(packed)
        if ((pos.x shr 4) != key.x() || (pos.z shr 4) != key.z()) throw IOException("Delta position does not belong to chunk")
    }

    private fun trackPosition(overlay: Overlay, position: Long) {
        if (overlay.trackedBlockEntities.add(position)) overlay.estimatedBytes += TRACKED_POSITION_BYTES
        overlay.blockEntityHistory.getOrPut(position) { mutableListOf() }
    }

    private fun appendEvent(overlay: Overlay, position: Long, event: BeEntry) {
        overlay.blockEntityHistory.getOrPut(position) { mutableListOf() }.add(event)
        overlay.eventCount++
        overlay.estimatedBytes += EVENT_ENTRY_BYTES
        if (event.replay is ReplayEvent.BlockEntity) {
            overlay.estimatedBytes += ClientChunkSnapshot.estimateTag(event.replay.tag) + event.replay.type.length * 2L
        }
    }

    private fun checkTag(tag: CompoundTag) {
        if (ClientChunkSnapshot.estimateTag(tag) > MAX_OVERLAY_BYTES) throw IOException("Block entity NBT exceeds delta limit")
    }

    private fun estimateOverlay(overlay: Overlay): Long = 128L + overlay.blocks.size * BLOCK_ENTRY_BYTES + overlay.trackedBlockEntities.size * TRACKED_POSITION_BYTES +
        overlay.blockEntityHistory.values.sumOf { entries -> entries.sumOf { entry ->
            EVENT_ENTRY_BYTES + if (entry.replay is ReplayEvent.BlockEntity) ClientChunkSnapshot.estimateTag(entry.replay.tag) + entry.replay.type.length * 2L else 0L
        } } + (overlay.biomes?.size ?: 0)

    private fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; if (output.size() + count > max) throw IOException("Chunk cache record exceeds limit"); output.write(buffer, 0, count) }
        return output.toByteArray()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8); if (bytes.size !in 1..MAX_NAME_BYTES) throw IOException("Invalid name length")
        out.writeShort(bytes.size); out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readUnsignedShort(); if (length !in 1..MAX_NAME_BYTES) throw IOException("Invalid name length")
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Chunk delta store is closed")
        if (failed) throw IOException("Chunk delta store is failed")
    }

    companion object {
        @Throws(IOException::class)
        private fun decodeTerrainSections(
            base: Snapshot,
            blockStates: Map<Long, BlockStateDelta>,
            biomeData: ByteArray?,
            biomes: Registry<Biome>,
        ): Array<LevelChunkSection> {
            val sections = ClientChunkBinaryCodec.decodeSections(base, biomes)
            for ((packed, delta) in blockStates) {
                val pos = BlockPos.of(packed)
                val sectionIndex = (pos.y shr 4) - base.minSection()
                if (sectionIndex !in sections.indices) throw IOException("Delta block is outside base section range")
                val state = Block.BLOCK_STATE_REGISTRY.byId(delta.stateId) ?: throw IOException("Unknown block state id")
                sections[sectionIndex].setBlockState(pos.x and 15, pos.y and 15, pos.z and 15, state, false)
            }
            biomeData?.let { data ->
                val buffer = FriendlyByteBuf(Unpooled.wrappedBuffer(data))
                try {
                    for (section in sections) section.readBiomes(buffer)
                    if (buffer.isReadable) throw IOException("Trailing biome palette data")
                } catch (failure: RuntimeException) {
                    throw IOException("Invalid biome palette delta", failure)
                } finally { buffer.release() }
            }
            return sections
        }

        private const val BASE_MAGIC = 0x52444342 // RDCB
        private const val DELTA_MAGIC = 0x52444344 // RDCD
        private const val FORMAT_VERSION = 1
        private const val MAX_RECORD_BYTES = ClientChunkSnapshot.MAX_SNAPSHOT_BYTES
        private const val BASE_HEADER_BYTES = 34
        private const val MAX_OVERLAY_BYTES = ClientChunkSnapshot.MAX_SNAPSHOT_BYTES
        private const val MAX_BIOME_BYTES = 2 * 1024 * 1024
        private const val MAX_NAME_BYTES = 2048
        private const val MAX_BLOCK_CHANGES = 65_536
        private const val MAX_BE_EVENTS = 65_536
        private const val MAX_GENERATIONS = 4096
        private const val MAX_OVERLAYS = 128
        private const val MAX_RETAINED_BYTES = 64L * 1024 * 1024
        private const val MAX_OPEN_REGIONS = 32
        private const val BLOCK_ENTRY_BYTES = 48L
        private const val TRACKED_POSITION_BYTES = 8L
        private const val EVENT_ENTRY_BYTES = 32L
    }
}
