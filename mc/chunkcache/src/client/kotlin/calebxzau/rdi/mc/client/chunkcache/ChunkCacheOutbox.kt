package calebxzau.rdi.mc.client.chunkcache

/**
 * Game-thread-owned outgoing offers and cancels, sent together once per client tick.
 *
 * Preparation finishes one chunk at a time, so sending on every completion produced one-entry offer packets.
 * Holding them until the tick ends fills each packet instead. A cancel for an offer that never left is settled
 * here: the server never saw that id, so the caller retires the pin locally and nothing is sent.
 */
class ChunkCacheOutbox<O>(private val idOf: (O) -> Long) {
    private val offers = ArrayList<O>()
    private val cancels = ArrayList<Long>()

    val pendingOffers: Int get() = offers.size
    val pendingCancels: Int get() = cancels.size

    fun offer(offer: O) {
        offers.add(offer)
    }

    /** Queues cancels and returns the ids that were never sent, whose pins the caller retires now. */
    fun cancel(ids: List<Long>): List<Long> {
        val unsent = ArrayList<Long>()
        for (id in ids) {
            if (offers.removeIf { idOf(it) == id }) unsent.add(id) else cancels.add(id)
        }
        return unsent
    }

    /**
     * Sends cancels first, so the server frees ledger room before new offers, then at most [maxOffers] offers.
     * The server rejects more than one batch of offers per tick; the rest wait for the next client tick.
     */
    fun drain(
        maxCancelsPerPacket: Int,
        maxOffers: Int,
        sendCancels: (List<Long>) -> Unit,
        sendOffers: (List<O>) -> Unit,
    ) {
        require(maxCancelsPerPacket > 0 && maxOffers > 0)
        if (cancels.isNotEmpty()) {
            cancels.chunked(maxCancelsPerPacket).forEach(sendCancels)
            cancels.clear()
        }
        if (offers.isNotEmpty()) {
            val batch = offers.subList(0, minOf(maxOffers, offers.size))
            sendOffers(ArrayList(batch))
            batch.clear()
        }
    }
}
