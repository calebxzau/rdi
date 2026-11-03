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
        "cargoTarget" to "x86_64-pc-windows-msvc",
        "cargoOutputTarget" to "x86_64-pc-windows-msvc",
        "jnaResourcePrefix" to "win32-x86-64",
        "localProxyLibraryFile" to "rdi_local_proxy.dll",
    )
    "linux-x64" -> mapOf(
        "lwjglNatives" to "natives-linux",
        "zstdClassifier" to "linux_amd64",
        "sqliteClassifier" to "natives-linux",
        "sqliteNativePath" to "Linux/x86_64/libsqlitejdbc.so",
        "ffmpegClassifier" to "linux-x86_64-gpl",
        // The suffix selects the highest glibc the library may need; it is not part of the output path.
        "cargoTarget" to "x86_64-unknown-linux-gnu.2.35",
        "cargoOutputTarget" to "x86_64-unknown-linux-gnu",
        "jnaResourcePrefix" to "linux-x86-64",
        "localProxyLibraryFile" to "librdi_local_proxy.so",
    )
    "macos-arm64" -> mapOf(
        "lwjglNatives" to "natives-macos-arm64",
        "zstdClassifier" to "darwin_aarch64",
        "sqliteClassifier" to "natives-mac",
        "sqliteNativePath" to "Mac/aarch64/libsqlitejdbc.dylib",
        "ffmpegClassifier" to "macosx-arm64-gpl",
        "cargoTarget" to "aarch64-apple-darwin",
        "cargoOutputTarget" to "aarch64-apple-darwin",
        "jnaResourcePrefix" to "darwin-aarch64",
        "localProxyLibraryFile" to "librdi_local_proxy.dylib",
    )
    else -> throw GradleException("不支持的rdi.targetPlatform: $rdiTargetPlatform")
}

// Compose Desktop names its per-platform artifacts desktop-jvm-<platform id>, matching ours.
extra["rdiTargetPlatform"] = rdiTargetPlatform
nativeCoordinates.forEach { (name, value) -> extra[name] = value }
