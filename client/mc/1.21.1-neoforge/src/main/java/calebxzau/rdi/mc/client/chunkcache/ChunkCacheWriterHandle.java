package calebxzau.rdi.mc.client.chunkcache;

/** Lifecycle operations shared by cache workers; awaiting is forbidden on the game thread except shutdown. */
public interface ChunkCacheWriterHandle {
    void discardPending();
    void closeAsync();
    void awaitClosed() throws InterruptedException;
    boolean awaitClosed(long timeoutMillis) throws InterruptedException;
}
