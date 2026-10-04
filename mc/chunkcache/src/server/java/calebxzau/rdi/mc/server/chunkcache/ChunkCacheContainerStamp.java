package calebxzau.rdi.mc.server.chunkcache;

/** Mixed into PalettedContainer: increments after every content mutation, never on palette resize. */
public interface ChunkCacheContainerStamp {
    int rdi$modCount();
}
