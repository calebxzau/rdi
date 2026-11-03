package calebxzau.rdi.mc.client;

import org.jetbrains.annotations.Nullable;

/** Implemented by the login hello packet, which carries a join ticket when connecting to a DM room. */
public interface DmHelloTicket {
    @Nullable
    String rdi$joinTicket();
}
