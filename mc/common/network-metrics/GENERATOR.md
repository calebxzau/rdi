# 1.20 packet class names

The `generatePacketClassNames` Gradle task runs
`calebxzau.rdi.mc.metrics.generator.GeneratePacketClassNames` against the
Mojang-named Minecraft inputs and writes
`PacketClassNames20.java` under the build's generated sources directory. The
generator reads class headers with ASM; it does not load Minecraft classes or
run in the server. The generated source is compiled with the server and its
class references are remapped by the loader build.

After changing the generator or its input configuration, run
`generatePacketClassNames` from the 1.20 server project and inspect the
generated source. No Gradle validation was run for this change.
