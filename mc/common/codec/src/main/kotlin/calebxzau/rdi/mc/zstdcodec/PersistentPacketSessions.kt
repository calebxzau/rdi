package calebxzau.rdi.mc.zstdcodec

import io.netty.channel.Channel
import io.netty.util.AttributeKey

/**
 * Server end of persistent packets on one connection, created from the client's offer during login.
 *
 * Only the connection's encoder uses it, on the event loop, once Play starts.
 */
class PersistentPacketServerSession(offer: PersistentPacketOffer, internal val store: PersistentPacketServerStore) {
    /** Per kind, the packets the client holds, newest first. */
    private val held = HashMap<Int, ArrayDeque<PersistentPacketHash>>()

    init {
        offer.entries.forEach { held.getOrPut(it.kind) { ArrayDeque() }.addLast(it.hash) }
    }

    internal fun holds(kind: Int, hash: PersistentPacketHash): Boolean = held[kind]?.contains(hash) == true

    /** Candidate delta bases, newest first. */
    internal fun bases(kind: Int): List<PersistentPacketHash> = held[kind]?.toList().orEmpty()

    /** The client keeps every restored packet for the rest of the connection. */
    internal fun sent(kind: Int, hash: PersistentPacketHash, content: ByteArray) {
        val hashes = held.getOrPut(kind) { ArrayDeque() }
        hashes.remove(hash)
        hashes.addFirst(hash)
        while (hashes.size > PersistentPacketClientSession.HELD_PER_KIND) hashes.removeLast()
        store.sent(kind, hash, content)
    }
}

/**
 * Client end of persistent packets on one connection. [loaded] holds the offered packets, already
 * verified; [onReceived] stores a newly restored packet, and must not block the event loop.
 *
 * Only the connection's decoder uses it, on the event loop, once Play starts.
 */
class PersistentPacketClientSession(
    loaded: Map<PersistentPacketHash, Pair<Int, ByteArray>>,
    private val onReceived: (Int, PersistentPacketHash, ByteArray) -> Unit,
) {
    /** Insertion order is age, oldest first. */
    private val contents = LinkedHashMap<PersistentPacketHash, Pair<Int, ByteArray>>(loaded)

    internal fun content(hash: PersistentPacketHash): ByteArray? = contents[hash]?.second

    /** Mirrors [PersistentPacketServerSession.sent], so both ends forget the same older packets. */
    internal fun received(kind: Int, hash: PersistentPacketHash, content: ByteArray) {
        contents.remove(hash)
        contents[hash] = kind to content
        val ofKind = contents.entries.filter { it.value.first == kind }
        ofKind.take((ofKind.size - HELD_PER_KIND).coerceAtLeast(0)).forEach { contents.remove(it.key) }
        runCatching { onReceived(kind, hash, content) }.onFailure { error ->
            LOGGER.log(System.Logger.Level.ERROR, "Failed to keep persistent packet ${hash} of ${PersistentPacketKind.name(kind)}", error)
        }
    }

    companion object {
        private val LOGGER: System.Logger = System.getLogger(PersistentPacketClientSession::class.java.name)

        /** Packets of one kind that a client offers and keeps in memory during a connection. */
        const val HELD_PER_KIND: Int = 2
    }
}

/** Channel attributes that carry the sessions from login, before compression starts, to the codec. */
internal object PersistentPacketSessions {
    private val SERVER = AttributeKey.valueOf<PersistentPacketServerSession>("rdi.pcache.server")
    private val CLIENT = AttributeKey.valueOf<PersistentPacketClientSession>("rdi.pcache.client")

    fun attach(channel: Channel, session: PersistentPacketServerSession) = channel.attr(SERVER).set(session)

    fun attach(channel: Channel, session: PersistentPacketClientSession) = channel.attr(CLIENT).set(session)

    fun server(channel: Channel): PersistentPacketServerSession? = channel.attr(SERVER).get()

    fun client(channel: Channel): PersistentPacketClientSession? = channel.attr(CLIENT).get()
}
