package calebxzau.rdi.mc.server.chunkcache;

import it.unimi.dsi.fastutil.longs.LongSet;

/** Mixed into PlayerChunkSender: the chunks still waiting to be sent, read by the warm-up summary. */
public interface ChunkCachePendingChunks {
    LongSet rdi$pendingChunks();
}
