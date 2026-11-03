import com.sun.jna.Library;
import com.sun.jna.Native;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Loads the released library the way the launcher does: JNA on a current JDK.
 *
 * <p>java -cp jna.jar -Djna.library.path=DIR client/local-proxy-rs/jna-smoke/JnaSmoke.java
 */
public class JnaSmoke {
    public interface Api extends Library {
        int rdi_lp_abi_version();

        int rdi_lp_start(byte[] bindHost, short preferredPort, byte[] nodes, short[] outPort);

        int rdi_lp_last_error(byte[] buffer, int length);

        int rdi_lp_stop(int timeoutMs);
    }

    private static final int EXPECTED_ABI = 1;

    private static byte[] text(String value) {
        return (value + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private static void check(Api api, int code, String step) {
        if (code < 0) {
            byte[] buffer = new byte[512];
            int length = Math.max(api.rdi_lp_last_error(buffer, buffer.length), 0);
            String detail = new String(buffer, 0, length, StandardCharsets.UTF_8);
            throw new IllegalStateException(step + " failed: " + code + " " + detail);
        }
    }

    private static int start(Api api) {
        short[] port = new short[1];
        check(api, api.rdi_lp_start(text("127.0.0.1"), (short) 0, text("127.0.0.1:1"), port), "start");
        return port[0] & 0xFFFF;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(System.getProperty("java.version") + " " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch"));
        Api api = Native.load("rdi_local_proxy", Api.class);
        int abi = api.rdi_lp_abi_version();
        if (abi != EXPECTED_ABI) {
            throw new IllegalStateException("unexpected ABI " + abi);
        }

        int port = start(api);
        try (Socket game = new Socket()) {
            game.connect(new InetSocketAddress("127.0.0.1", port), 2_000);
        }
        check(api, api.rdi_lp_stop(2_000), "stop");
        check(api, api.rdi_lp_stop(2_000), "second stop");

        start(api);
        check(api, api.rdi_lp_stop(2_000), "stop after restart");
        System.out.println("local proxy library loaded, started and stopped");
    }
}
