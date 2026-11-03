package calebxzhou.rdi.mc.server.network;

/** Mixed into the entity move and head rotation packets: the entity they update, read by the idle gate. */
public interface IdleEntityPacket {
    int rdi$entityId();
}
