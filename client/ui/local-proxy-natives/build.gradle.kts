plugins {
    `java-library`
}

apply(from = "../../gradle/target-platform.gradle.kts")
val rdiTargetPlatform: String by extra
val cargoTarget: String by extra
val cargoOutputTarget: String by extra
val jnaResourcePrefix: String by extra
val localProxyLibraryFile: String by extra

// Ships the Rust local proxy of the target platform where JNA looks for it on the classpath.
val crateDir = file("../../local-proxy-rs")
val protocolCrateDir = file("../../../common/session-resume-rs")
val cargoTargetDir = layout.buildDirectory.dir("cargo")
val builtLibrary = cargoTargetDir.map { it.file("$cargoOutputTarget/release/$localProxyLibraryFile") }

val buildLocalProxy by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the local proxy library for the target platform."
    workingDir = crateDir
    inputs.dir(crateDir.resolve("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(crateDir.resolve("Cargo.toml"), crateDir.resolve("Cargo.lock"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(protocolCrateDir.resolve("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(protocolCrateDir.resolve("Cargo.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("cargoTarget", cargoTarget)
    outputs.file(builtLibrary)

    // Windows releases are built on Windows; the others are cross-built with Zig.
    val cargoCommand = if (rdiTargetPlatform == "windows-x64") "build" else "zigbuild"
    val targetDir = cargoTargetDir.get().asFile.absolutePath
    commandLine("cargo", cargoCommand, "--release", "--locked", "--lib", "--target", cargoTarget, "--target-dir", targetDir)

    // Plain values only: the configuration cache cannot keep a reference to this script.
    val platform = rdiTargetPlatform
    val rustupTarget = cargoOutputTarget
    doFirst {
        val toolHint = if (cargoCommand == "build") {
            "请安装Rust: https://rustup.rs"
        } else {
            "请安装Rust、Zig 0.14.1和cargo-zigbuild，并执行rustup target add $rustupTarget"
        }
        val probe = if (cargoCommand == "build") listOf("cargo", "--version") else listOf("cargo-zigbuild", "--version")
        val available = runCatching {
            ProcessBuilder(probe)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
        if (!available) {
            throw GradleException("无法为${platform}构建本地代理: 找不到cargo $cargoCommand。$toolHint")
        }
    }
}

tasks.named<Jar>("jar") {
    archiveFileName.set("rdi-local-proxy-natives-$rdiTargetPlatform.jar")
    from(buildLocalProxy.map { builtLibrary.get() }) {
        into(jnaResourcePrefix)
    }
}
