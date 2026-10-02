package calebxzhou.rdi.mc.common;

import java.nio.charset.StandardCharsets;
import java.util.*;

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
    public static boolean SHOW_SET_SYNC_CHUNKS=false;
    public static boolean SHOW_NOW_SYNC_CHUNK=false;
    //维度id与同步区块；SectionPos.index固定为0，仅复用轻量坐标容器。
    public static Map<String, List<calebxzhou.rdi.mc.common.SectionPos>> SYNC_CHUNKS = new HashMap<>();
}
