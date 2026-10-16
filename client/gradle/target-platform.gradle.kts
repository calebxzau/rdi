// Native artifact coordinates for the platform the launcher UI libraries are packaged for.
// Select it with -Prdi.targetPlatform=<platform id>. The default keeps builds that never pass it
// (Windows development, the MC mod builds that include :mediaproc) on windows-x64.
//
// Applied with apply(from = ...); read the values through `val name: String by extra`.

val rdiTargetPlatform = providers.gradleProperty("rdi.targetPlatform").getOrElse("windows-x64")

val nativeCoordinates: Map<String, String> = when (rdiTargetPlatform) {
    "windows-x64" -> mapOf(
        "lwjglNatives" to "natives-windows",
        "zstdClassifier" to "win_amd64",
        "sqliteClassifier" to "natives-windows",
        "sqliteNativePath" to "Windows/x86_64/sqlitejdbc.dll",
        "ffmpegClassifier" to "windows-x86_64-gpl",
    )
    "linux-x64" -> mapOf(
        "lwjglNatives" to "natives-linux",
        "zstdClassifier" to "linux_amd64",
        "sqliteClassifier" to "natives-linux",
        "sqliteNativePath" to "Linux/x86_64/libsqlitejdbc.so",
        "ffmpegClassifier" to "linux-x86_64-gpl",
    )
    "macos-arm64" -> mapOf(
        "lwjglNatives" to "natives-macos-arm64",
        "zstdClassifier" to "darwin_aarch64",
        "sqliteClassifier" to "natives-mac",
        "sqliteNativePath" to "Mac/aarch64/libsqlitejdbc.dylib",
        "ffmpegClassifier" to "macosx-arm64-gpl",
    )
    else -> throw GradleException("不支持的rdi.targetPlatform: $rdiTargetPlatform")
}

// Compose Desktop names its per-platform artifacts desktop-jvm-<platform id>, matching ours.
extra["rdiTargetPlatform"] = rdiTargetPlatform
nativeCoordinates.forEach { (name, value) -> extra[name] = value }
