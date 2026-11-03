import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

apply(from = "../gradle/target-platform.gradle.kts")
val ffmpegClassifier: String by extra

repositories {
    mavenCentral()
    mavenLocal()
}

group = "calebxzhou.rdi"
//version = libs.versions.app.get()
/*
val javaVersion = 21

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
}*/

dependencies {
    implementation("org.bytedeco:javacv:1.5.13") {
        isTransitive = false
    }
    implementation("org.bytedeco:javacpp:1.5.13")
    implementation("org.bytedeco:ffmpeg:8.0.1-1.5.13")
    runtimeOnly("org.bytedeco:ffmpeg:8.0.1-1.5.13:$ffmpegClassifier")
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    rootProject.findProject(":assets")?.let { testImplementation(it) }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val nativePreloadTestClass = "calebxzau.rdi.mediaproc.FfmpegNativePreloaderTest"

tasks.named<Test>("test") {
    filter { excludeTestsMatching(nativePreloadTestClass) }
}

val testFfmpegNativeDir = layout.buildDirectory.dir("test-ffmpeg-natives")
val extractTestFfmpegNatives = tasks.register<Sync>("extractTestFfmpegNatives") {
    val classifier = ffmpegClassifier
    from({
        configurations.testRuntimeClasspath.get()
            .filter { it.name.endsWith("-$classifier.jar") }
            .map { zipTree(it) }
    })
    // Windows and macOS put the version before the extension; Linux puts it after: libavutil.so.60.
    include(listOf("*.dll", "*.so*", "*.dylib").map { "org/bytedeco/ffmpeg/$classifier/$it" })
    eachFile { path = name }
    includeEmptyDirs = false
    into(testFfmpegNativeDir)
}

// Loads FFmpeg the way the game does: JavaCPP library finding disabled, natives only from preloadpath.
tasks.register<Test>("nativePreloadTest") {
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(extractTestFfmpegNatives)
    filter { includeTestsMatching(nativePreloadTestClass) }
    systemProperty("org.bytedeco.javacpp.pathsFirst", "true")
    systemProperty("org.bytedeco.javacpp.findLibraries", "false")
    systemProperty("org.bytedeco.javacpp.platform.preloadpath", testFfmpegNativeDir.get().asFile.absolutePath)
}
base {
    archivesName.set("rdi-mediaproc")
}