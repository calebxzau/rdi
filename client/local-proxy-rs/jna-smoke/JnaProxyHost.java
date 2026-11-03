import com.sun.jna.Library;
import com.sun.jna.Native;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Hosts the released library in a JVM the way the launcher does, behind the same stdin and stdout
 * as rdi-local-proxy-cli, so server/proxy-rs/scripts/resume_game_e2e.py can test real games on it.
 *
 * <p>java -cp jna.jar -Djna.library.path=DIR JnaProxyHost.java --listen 127.0.0.1:6370 --nodes a:1,b:2
 */
public class JnaProxyHost {
    public interface Api extends Library {
        int rdi_lp_abi_version();

        int rdi_lp_start(byte[] bindHost, short preferredPort, byte[] nodes, short[] outPort);

        int rdi_lp_set_nodes(byte[] nodes);

        int rdi_lp_switch_node(byte[] address);

        int rdi_lp_poll_events(byte[] buffer, int length);

        int rdi_lp_last_error(byte[] buffer, int length);

        int rdi_lp_stop(int timeoutMs);
    }

    private static final int EXPECTED_ABI = 1;
    private static final long POLL_INTERVAL_MS = 500;
    private static final long STARTED_AT = System.nanoTime();

    private static byte[] text(String value) {
        return (value + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private static String argument(String[] args, String name) {
        for (int index = 0; index + 1 < args.length; index++) {
            if (args[index].equals(name)) {
                return args[index + 1];
            }
        }
        throw new IllegalArgumentException("missing " + name);
    }

    private static void check(Api api, int code, String step) {
        if (code < 0) {
            byte[] buffer = new byte[4096];
            int length = Math.max(api.rdi_lp_last_error(buffer, buffer.length), 0);
            String detail = new String(buffer, 0, length, StandardCharsets.UTF_8);
            throw new IllegalStateException(step + " failed: " + code + " " + detail);
        }
    }

    private static synchronized void print(String line) {
        System.out.println((System.nanoTime() - STARTED_AT) / 1_000_000 + " " + line);
        System.out.flush();
    }

    private static String capitalized(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** Rewrites one event line in the form rdi-local-proxy-cli prints. */
    static String describe(String line) {
        String[] fields = line.split("\t", -1);
        return switch (fields[0]) {
            case "attached" -> "session=%s Attached { node: \"%s\", reason: %s, elapsed_ms: %s }"
                    .formatted(fields[1], fields[2], capitalized(fields[3]), fields[4]);
            case "path_lost" -> "session=%s PathLost { reason: \"%s\" }".formatted(fields[1], fields[2]);
            case "switch_failed" -> "session=%s SwitchFailed { node: \"%s\", error: \"%s\" }"
                    .formatted(fields[1], fields[2], fields[3]);
            case "ended" -> "session=%s Ended { reason: %s %s }".formatted(fields[1], capitalized(fields[2]), fields[3]);
            default -> "other " + line;
        };
    }

    private static void drain(Api api, byte[] buffer) {
        while (true) {
            int count = api.rdi_lp_poll_events(buffer, buffer.length);
            check(api, count, "poll");
            if (count == 0) {
                return;
            }
            String lines = new String(buffer, 0, count, StandardCharsets.UTF_8);
            lines.lines().filter(line -> !line.isEmpty()).forEach(line -> print(describe(line)));
        }
    }

    public static void main(String[] args) throws Exception {
        String listen = argument(args, "--listen");
        String nodes = argument(args, "--nodes").replace(',', '\n');
        int colon = listen.lastIndexOf(':');
        String host = listen.substring(0, colon);
        int preferredPort = Integer.parseInt(listen.substring(colon + 1));

        Api api = Native.load("rdi_local_proxy", Api.class);
        if (api.rdi_lp_abi_version() != EXPECTED_ABI) {
            throw new IllegalStateException("unexpected ABI " + api.rdi_lp_abi_version());
        }
        short[] port = new short[1];
        check(api, api.rdi_lp_start(text(host), (short) preferredPort, text(nodes), port), "start");
        print("ready " + host + ":" + (port[0] & 0xFFFF));

        Thread poller = new Thread(() -> {
            byte[] buffer = new byte[64 * 1024];
            try {
                while (true) {
                    drain(api, buffer);
                    Thread.sleep(POLL_INTERVAL_MS);
                }
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
            }
        });
        poller.setDaemon(true);
        poller.start();

        try (BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = commands.readLine()) != null) {
                if (line.startsWith("switch ")) {
                    check(api, api.rdi_lp_switch_node(text(line.substring(7).trim())), "switch");
                } else if (line.startsWith("nodes ")) {
                    check(api, api.rdi_lp_set_nodes(text(line.substring(6).trim().replace(',', '\n'))), "nodes");
                } else if (line.equals("quit")) {
                    break;
                }
            }
        }
        poller.interrupt();
        poller.join(1_000);
        check(api, api.rdi_lp_stop(3_000), "stop");
    }
}
