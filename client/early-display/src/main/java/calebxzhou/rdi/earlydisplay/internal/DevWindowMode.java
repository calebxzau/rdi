package calebxzhou.rdi.earlydisplay.internal;

import org.jetbrains.annotations.Nullable;

/**
 * How the game window starts, chosen with {@value #PROPERTY} so that an agent launching dev clients
 * does not interrupt whoever is using the machine. Players never set it and get {@link #Normal}.
 */
enum DevWindowMode {
    /** Shows and focuses the window as usual. */
    Normal,
    /**
     * Shows the window past the right edge of every monitor without taking focus, so it covers nothing
     * but keeps rendering. There is no minimized mode: a minimized window stalls the game's loading
     * screen, and the game's Window takes a 1x1 framebuffer if it is built while minimized.
     */
    Background;

    static final String PROPERTY = "rdi.dev.window";

    /** Null when {@code value} is set but is not a known mode. */
    @Nullable
    static DevWindowMode parse(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return Normal;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "normal" -> Normal;
            case "background" -> Background;
            default -> null;
        };
    }
}
