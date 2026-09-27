package calebxzau.rdi.mc.v20.client;

import java.util.UUID;

public record RTabRow(String text, UUID playerId, int color) {
    public boolean isPlayer() {
        return playerId != null;
    }
}
