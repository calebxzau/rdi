package calebxzau.rdi.mc.server.chunkcache;

/** Mixed into LevelChunkSection: a lazily created, memory-only terrain hash cache. */
public interface ChunkCacheSectionHashHolder {
    SectionHashCache rdi$hashCache();
}
