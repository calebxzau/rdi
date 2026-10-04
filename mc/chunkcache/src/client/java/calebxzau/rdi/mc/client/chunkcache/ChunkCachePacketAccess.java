package calebxzau.rdi.mc.client.chunkcache;

/** Listener-bound cache context used when a reuse response becomes a vanilla chunk packet. */
public interface ChunkCachePacketAccess {
    RdiChunkCache.PacketCaptureContext rdi$cacheContext();
}
