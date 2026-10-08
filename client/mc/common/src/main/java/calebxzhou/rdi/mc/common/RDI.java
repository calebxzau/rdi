package calebxzhou.rdi.mc.common;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * calebxzhou @ 2026-01-06 19:34
 */
public class RDI {
    public static final boolean DEBUG;
    public static final String IHQ_URL;
    public static final String GAME_IP;
    public static final String HOST_NAME;
    // Optional: only the RDI room launch path supplies this identity.
    public static final String HOST_ID = System.getProperty("rdi.host.id");
    public static int HOST_PORT;
    //nullable
    public static UUID PLAYER_ID;
    //nullable
    public static String PLAYER_NAME;
    static {
        DEBUG = Boolean.parseBoolean(System.getProperty("rdi.debug", "false"));
        String playData = System.getProperty("rdi.play");
        if (playData != null) {
            byte[] decodedBytes = Base64.getDecoder().decode(playData.trim());
            String decoded = new String(decodedBytes, StandardCharsets.UTF_8);

            String[] lines = decoded.split("\\r?\\n");
            if (lines.length < 6) {
                throw new IllegalStateException("RDI参数错误，请重新复制参数！");
            }
            IHQ_URL = lines[0].trim();
            GAME_IP = lines[1].trim();
            HOST_NAME = lines[2].trim();
            HOST_PORT = Integer.parseInt(lines[3].trim());
            PLAYER_ID = UUID.fromString(lines[4].trim());
            PLAYER_NAME = lines[5].trim();
        }else throw new IllegalStateException("RDI参数错误，找不到游玩参数！");
    }

    public static String getTextureQueryUrl(UUID profileId, String authlibVer) {
        return IHQ_URL + "/mc-profile/" + profileId + "/clothes?authlibVer=" + authlibVer;
    }

    /** Called on the client thread after game loading. Window APIs stay in the client adapter. */
    public static void applyWindowProperties(
            Consumer<BufferedImage> setIcon,
            Consumer<String> setTitle,
            BiConsumer<String, Throwable> logError
    ) {
        String iconPath = System.getProperty("rdi.window.icon");
        if (iconPath != null) {
            BufferedImage icon = readWindowIcon(iconPath, logError);
            if (icon != null) {
                try {
                    setIcon.accept(icon);
                } finally {
                    icon.flush();
                }
            }
        }

        String title = System.getProperty("rdi.window.title");
        if (title != null) {
            if (title.isEmpty() || title.length() > 64) {
                logError.accept("忽略无效的rdi.window.title：标题长度必须为1至64个字符",
                        new IllegalArgumentException("Window title length: " + title.length()));
            } else {
                setTitle.accept(title+" @ rdi多人房间");
            }
        }
    }

    private static BufferedImage readWindowIcon(String path, BiConsumer<String, Throwable> logError) {
        try {
            BufferedImage image = ImageIO.read(new File(path));
            if (image == null) {
                throw new IOException("Unsupported or undecodable window icon image");
            }
            return image;
        } catch (IOException | RuntimeException exception) {
            logError.accept("无法读取或解码rdi.window.icon图标：" + path, exception);
            return null;
        }
    }
}
