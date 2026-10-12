package calebxzau.rdi.mc.zstdcodec

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.DecoderException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.concurrent.Executor
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class PersistentPacketTest {
    private val directory: Path = Files.createTempDirectory("rdi-pcache-test")

    @AfterTest
    fun cleanUp() = directory.deleteRecursively()

    @Test
    fun `a join without kept tables sends them whole and the client keeps them`() {
        val recipes = table(seed = 1, bytes = 64 * 1024)
        val pair = PersistentPair(offer = emptyList(), held = emptyMap())
        try {
            val frames = pair.send(recipes, PersistentPacketKind.RECIPES)
            assertEquals(PersistentPacketFormat.OP_FULL, opcode(frames.single()))
            assertTrue(frames.single().readableBytes() < recipes.size / 2, "a FULL frame is still compressed")
            assertRecords(listOf(recipes), pair.deliver(frames))
            assertEquals(listOf(PersistentPacketHash.of(recipes)), pair.kept.map { it.second })
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a held table leaves as a short reference`() {
        val recipes = table(seed = 2, bytes = 256 * 1024)
        val hash = PersistentPacketHash.of(recipes)
        val pair = PersistentPair(
            offer = listOf(PersistentPacketOffer.Entry(PersistentPacketKind.RECIPES, hash)),
            held = mapOf(hash to (PersistentPacketKind.RECIPES to recipes)),
        )
        try {
            val frames = pair.send(recipes, PersistentPacketKind.RECIPES)
            assertEquals(PersistentPacketFormat.OP_HIT, opcode(frames.single()))
            assertEquals(PersistentPacketFormat.HEADER_BYTES, frames.single().readableBytes())
            assertRecords(listOf(recipes), pair.deliver(frames))
            assertTrue(pair.kept.isEmpty(), "a reference stores nothing new")
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a changed table leaves as a delta against the held one`() {
        val old = table(seed = 3, bytes = 512 * 1024)
        val new = old.copyOf().also { changed ->
            for (offset in 100_000 until 100_400) changed[offset] = (changed[offset] + 1).toByte()
        } + "minecraft:new_recipe".toByteArray()
        val oldHash = PersistentPacketHash.of(old)
        val store = serverStore().also { it.sent(PersistentPacketKind.RECIPES, oldHash, old) }
        val pair = PersistentPair(
            offer = listOf(PersistentPacketOffer.Entry(PersistentPacketKind.RECIPES, oldHash)),
            held = mapOf(oldHash to (PersistentPacketKind.RECIPES to old)),
            store = store,
        )
        val full = PersistentPair(offer = emptyList(), held = emptyMap())
        try {
            val frames = pair.send(new, PersistentPacketKind.RECIPES)
            assertEquals(PersistentPacketFormat.OP_DELTA, opcode(frames.single()))
            val fullFrame = full.send(new, PersistentPacketKind.RECIPES).single()
            assertTrue(
                frames.single().readableBytes() * 20 < fullFrame.readableBytes(),
                "delta=${frames.single().readableBytes()} full=${fullFrame.readableBytes()}",
            )
            fullFrame.release()
            assertRecords(listOf(new), pair.deliver(frames))
            assertEquals(listOf(PersistentPacketHash.of(new)), pair.kept.map { it.second })

            // The new table is held now, so sending it again is a reference.
            assertEquals(PersistentPacketFormat.OP_HIT, pair.roundTrip(new, PersistentPacketKind.RECIPES))
        } finally {
            pair.close()
            full.close()
        }
    }

    @Test
    fun `a base the server lost leaves the table whole`() {
        val old = table(seed = 4, bytes = 32 * 1024)
        val new = table(seed = 5, bytes = 32 * 1024)
        val oldHash = PersistentPacketHash.of(old)
        val pair = PersistentPair(
            offer = listOf(PersistentPacketOffer.Entry(PersistentPacketKind.TAGS, oldHash)),
            held = mapOf(oldHash to (PersistentPacketKind.TAGS to old)),
        )
        try {
            val frames = pair.send(new, PersistentPacketKind.TAGS)
            assertEquals(PersistentPacketFormat.OP_FULL, opcode(frames.single()))
            assertRecords(listOf(new), pair.deliver(frames))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `a whole table continues the Zstd stream`() {
        val first = table(seed = 6, bytes = 48 * 1024)
        val second = table(seed = 7, bytes = 48 * 1024)
        val pair = PersistentPair(offer = emptyList(), held = emptyMap())
        try {
            assertTrue(ZstdCompressionPipeline.setInboundStreamIfAvailable(pair.client, true))
            ZstdCompressionPipeline.setOutboundStream(pair.server)
            assertEquals(emptyList(), pair.deliver(pair.drain()))
            val plain = ByteArray(2000) { (it % 13).toByte() }

            pair.server.writeOutbound(Unpooled.wrappedBuffer(plain))
            val frames = pair.drain() + pair.send(first, PersistentPacketKind.RECIPES) + pair.send(second, PersistentPacketKind.TAGS)
            assertEquals(listOf(PersistentPacketFormat.OP_FULL, PersistentPacketFormat.OP_FULL), frames.drop(1).map(::opcode))
            assertRecords(listOf(plain, first, second), pair.deliver(frames))
        } finally {
            pair.close()
        }
    }

    @Test
    fun `small or unmarked packets and connections without a session stay plain`() {
        val small = ByteArray(PersistentPacketFormat.MINIMUM_PACKET_BYTES - 1) { (it % 7).toByte() }
        val large = table(seed = 8, bytes = 16 * 1024)
        val pair = PersistentPair(offer = emptyList(), held = emptyMap())
        val plain = PersistentPair(offer = null, held = emptyMap())
        try {
            val frames = pair.send(small, PersistentPacketKind.RECIPES) + pair.send(large, PersistentPacketKind.NONE)
            assertTrue(frames.none { leadingVarInt(it) == PersistentPacketFormat.MARKER })
            assertRecords(listOf(small, large), pair.deliver(frames))

            val plainFrames = plain.send(large, PersistentPacketKind.RECIPES)
            assertTrue(plainFrames.none { leadingVarInt(it) == PersistentPacketFormat.MARKER })
            assertRecords(listOf(large), plain.deliver(plainFrames))
        } finally {
            pair.close()
            plain.close()
        }
    }

    @Test
    fun `the client rejects a frame it cannot restore exactly`() {
        val recipes = table(seed = 9, bytes = 8 * 1024)
        val hash = PersistentPacketHash.of(recipes)
        val unknown = PersistentPair(
            offer = listOf(PersistentPacketOffer.Entry(PersistentPacketKind.RECIPES, hash)),
            held = emptyMap(),
        )
        try {
            assertFailsWith<DecoderException> { unknown.deliver(unknown.send(recipes, PersistentPacketKind.RECIPES)) }
        } finally {
            unknown.close()
        }

        val tampered = PersistentPair(offer = emptyList(), held = emptyMap())
        try {
            val frame = tampered.send(recipes, PersistentPacketKind.RECIPES).single()
            frame.setByte(frame.readerIndex() + PersistentPacketFormat.HEADER_BYTES - 1, frame.getByte(frame.readerIndex() + PersistentPacketFormat.HEADER_BYTES - 1).toInt() xor 1)
            assertFailsWith<DecoderException> { tampered.deliver(listOf(frame)) }
        } finally {
            tampered.close()
        }

        val sessionless = PersistentPair(offer = emptyList(), held = emptyMap(), clientSession = false)
        try {
            assertFailsWith<DecoderException> { sessionless.deliver(sessionless.send(recipes, PersistentPacketKind.RECIPES)) }
        } finally {
            sessionless.close()
        }
    }

    @Test
    fun `both ends forget the same older tables`() {
        val tables = (0 until 5).map { table(seed = 20 + it, bytes = 8 * 1024) }
        val pair = PersistentPair(offer = emptyList(), held = emptyMap())
        try {
            tables.forEach { pair.roundTrip(it, PersistentPacketKind.RECIPES) }
            // Only the two newest stay held: those are references, the third newest is sent again.
            assertEquals(PersistentPacketFormat.OP_HIT, pair.roundTrip(tables[4], PersistentPacketKind.RECIPES))
            assertEquals(PersistentPacketFormat.OP_HIT, pair.roundTrip(tables[3], PersistentPacketKind.RECIPES))
            assertTrue(pair.roundTrip(tables[2], PersistentPacketKind.RECIPES) != PersistentPacketFormat.OP_HIT)
        } finally {
            pair.close()
        }
    }

    @Test
    fun `the store keeps verified newest entries and drops corrupt ones`() {
        val store = PersistentPacketStore(directory, keepPerKind = 2)
        val tables = (0 until 3).map { table(seed = 40 + it, bytes = 6000) }
        val hashes = tables.map { PersistentPacketHash.of(it) }
        // Each save is the newest at first; the fixed times keep the order independent of the clock.
        tables.forEachIndexed { index, content ->
            store.save(PersistentPacketKind.TAGS, hashes[index], content).getOrThrow()
            Files.setLastModifiedTime(
                directory.resolve("tags").resolve("${hashes[index].toHex()}.bin"),
                java.nio.file.attribute.FileTime.fromMillis(1_000_000L * (index + 1)),
            )
        }

        assertEquals(listOf(hashes[2], hashes[1]), store.recent(PersistentPacketKind.TAGS, 5).getOrThrow())
        assertContentEquals(tables[1], store.load(PersistentPacketKind.TAGS, hashes[1]).getOrThrow())
        assertNull(store.load(PersistentPacketKind.TAGS, hashes[0]).getOrThrow())
        assertEquals(emptyList(), store.recent(PersistentPacketKind.RECIPES, 5).getOrThrow())

        directory.resolve("tags").resolve("${hashes[1].toHex()}.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store.load(PersistentPacketKind.TAGS, hashes[1]).isFailure)
        assertEquals(listOf(hashes[2]), store.recent(PersistentPacketKind.TAGS, 5).getOrThrow())
        assertTrue(directory.resolve("tags").listDirectoryEntries("*.tmp").isEmpty())
    }

    @Test
    fun `offers and queries round trip and reject malformed bodies`() {
        val offer = PersistentPacketOffer(
            PersistentPacketQuery.VERSION,
            listOf(
                PersistentPacketOffer.Entry(PersistentPacketKind.RECIPES, PersistentPacketHash.of(byteArrayOf(1))),
                PersistentPacketOffer.Entry(PersistentPacketKind.TAGS, PersistentPacketHash.of(byteArrayOf(2))),
            ),
        )
        assertEquals(offer, PersistentPacketOffer.decode(offer.encode()).getOrThrow())
        assertEquals(3 + 2 * 21, offer.encode().size)
        assertTrue(PersistentPacketOffer.decode(offer.encode() + 0).isFailure)
        assertTrue(PersistentPacketOffer.decode(offer.encode().copyOf(10)).isFailure)
        assertTrue(PersistentPacketOffer.decode(byteArrayOf(1, 0, 1, 0) + ByteArray(20)).isFailure, "kind 0 is invalid")

        assertEquals(PersistentPacketQuery.VERSION, PersistentPacketQuery.decodeQuery(PersistentPacketQuery.encodeQuery()).getOrThrow())
        assertTrue(PersistentPacketQuery.decodeQuery(byteArrayOf()).isFailure)
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            PersistentPacketHash.of(byteArrayOf()).toHex(),
            "SHA-1 of nothing",
        )
    }

    @Test
    fun `a base that looks like a trained dictionary is never used`() {
        val magic = byteArrayOf(0x37, 0xA4.toByte(), 0x30, 0xEC.toByte()) + ByteArray(5000)
        assertTrue(!PersistentPacketDelta.isUsableBase(magic))
        val base = table(seed = 50, bytes = 20_000)
        val target = base + byteArrayOf(9, 9, 9)
        assertContentEquals(target, PersistentPacketDelta.decode(base, PersistentPacketDelta.encode(base, target), target.size))
    }

    private fun serverStore(): PersistentPacketServerStore =
        PersistentPacketServerStore(PersistentPacketStore(directory.resolve("server"), 4), DIRECT)

    /** A server and a client connection whose sessions come from the given offer and held tables. */
    private inner class PersistentPair(
        offer: List<PersistentPacketOffer.Entry>?,
        held: Map<PersistentPacketHash, Pair<Int, ByteArray>>,
        store: PersistentPacketServerStore = serverStore(),
        clientSession: Boolean = true,
    ) {
        val server = channel()
        val client = channel()
        val kept = ArrayList<Pair<Int, PersistentPacketHash>>()

        init {
            ZstdCompressionPipeline.setup(server, 256, false, TEST_VAR_INT)
            ZstdCompressionPipeline.setup(client, 256, false, TEST_VAR_INT)
            if (offer != null) {
                ZstdCompressionPipeline.attachPersistentPackets(
                    server,
                    PersistentPacketServerSession(PersistentPacketOffer(PersistentPacketQuery.VERSION, offer), store),
                )
            }
            if (clientSession) {
                ZstdCompressionPipeline.attachPersistentPackets(
                    client,
                    PersistentPacketClientSession(held) { kind, hash, _ -> kept += kind to hash },
                )
            }
        }

        fun send(content: ByteArray, kind: Int): List<ByteBuf> {
            server.writeOutbound(ZstdSendingRecord(Unpooled.wrappedBuffer(content), ZstdBatchPolicy.Immediate, null, kind))
            return drain()
        }

        fun drain(): List<ByteBuf> {
            val frames = ArrayList<ByteBuf>()
            while (true) frames += server.readOutbound<ByteBuf>() ?: break
            return frames
        }

        /** Sends one persistent packet, checks that the client restores it exactly, and returns the opcode. */
        fun roundTrip(content: ByteArray, kind: Int): Int {
            val frame = send(content, kind).single()
            val opcode = opcode(frame)
            assertRecords(listOf(content), deliver(listOf(frame)))
            return opcode
        }

        fun deliver(frames: List<ByteBuf>): List<ByteArray> {
            frames.forEach { client.writeInbound(it) }
            val records = ArrayList<ByteArray>()
            while (true) {
                val record = client.readInbound<ByteBuf>() ?: break
                try {
                    records += bytes(record)
                } finally {
                    record.release()
                }
            }
            return records
        }

        fun close() {
            runCatching { server.finishAndReleaseAll() }
            runCatching { client.finishAndReleaseAll() }
        }

        private fun channel(): EmbeddedChannel = EmbeddedChannel().apply {
            pipeline().addLast("splitter", ChannelInboundHandlerAdapter())
            pipeline().addLast("decoder", ChannelInboundHandlerAdapter())
            pipeline().addLast("prepender", ChannelOutboundHandlerAdapter())
            pipeline().addLast("encoder", ChannelOutboundHandlerAdapter())
        }
    }

    private object TEST_VAR_INT : MinecraftVarIntCodec {
        override fun read(buffer: ByteBuf): Int {
            var value = 0
            var shift = 0
            repeat(5) {
                check(buffer.isReadable) { "Unterminated VarInt" }
                val current = buffer.readUnsignedByte().toInt()
                value = value or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) return value
                shift += 7
            }
            throw IllegalStateException("VarInt too big")
        }

        override fun write(buffer: ByteBuf, value: Int) {
            var current = value
            while (current and -0x80 != 0) {
                buffer.writeByte((current and 0x7F) or 0x80)
                current = current ushr 7
            }
            buffer.writeByte(current)
        }
    }

    private companion object {
        val DIRECT = Executor { it.run() }

        /** Recipe-like content: repeated identifiers with varying numbers, deterministic per seed. */
        fun table(seed: Int, bytes: Int): ByteArray {
            val random = Random(seed.toLong())
            val words = listOf("minecraft:", "create:", "crafting_shaped", "ingredient", "result", "count", "item", "tag", "#forge:ingots/")
            val builder = StringBuilder(bytes)
            while (builder.length < bytes) {
                builder.append(words[random.nextInt(words.size)]).append(random.nextInt(1000)).append(';')
            }
            return builder.substring(0, bytes).toByteArray()
        }

        fun leadingVarInt(frame: ByteBuf): Int = TEST_VAR_INT.read(frame.duplicate())

        fun opcode(frame: ByteBuf): Int {
            val duplicate = frame.duplicate()
            assertEquals(PersistentPacketFormat.MARKER, TEST_VAR_INT.read(duplicate))
            return duplicate.readUnsignedByte().toInt()
        }

        fun bytes(buffer: ByteBuf): ByteArray =
            ByteArray(buffer.readableBytes()).also { buffer.getBytes(buffer.readerIndex(), it) }

        fun assertRecords(expected: List<ByteArray>, actual: List<ByteArray>) {
            assertEquals(expected.size, actual.size, "record count")
            expected.zip(actual).forEachIndexed { index, (expectedRecord, actualRecord) ->
                assertContentEquals(expectedRecord, actualRecord, "record $index")
            }
        }
    }
}
