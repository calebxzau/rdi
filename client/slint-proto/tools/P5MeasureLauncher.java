// IDE-only launcher: credentials arrive as ephemeral environment overrides, never command arguments.
public class P5MeasureLauncher {
    // Reuse the IDE's JVM launch transport only. The configured application's main never runs:
    // successful measurement calls System.exit; any premain exception aborts JVM startup.
    public static void premain(String root) throws Exception {
        main(new String[]{root});
    }
    public static void main(String[] args) throws Exception {
        for (String key : new String[]{"RDI_PROTO_USR", "RDI_PROTO_PWD"}) {
            if (System.getenv(key) == null || System.getenv(key).isEmpty()) {
                throw new IllegalStateException("Test-account process environment is missing");
            }
        }
        var root = java.nio.file.Path.of(args[0]).toAbsolutePath();
        var process = new ProcessBuilder("pwsh.exe", "-NoProfile", "-File",
            root.resolve("tools/p5-measure-all.ps1").toString())
            .directory(root.toFile()).inheritIO().start();
        System.exit(process.waitFor());
    }
}
