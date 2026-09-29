package calebxzau.rdi.mc.metrics

/** Packet travel direction, independent of Minecraft protocol versions and loaders. */
enum class PacketDirection(val databaseValue: String) {
    S2C("s2c"),
    C2S("c2s"),
}
