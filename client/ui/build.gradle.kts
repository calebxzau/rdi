import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.tasks.Copy
import org.gradle.internal.fingerprint.classpath.impl.ClasspathFingerprintingStrategy.compileClasspath
import org.gradle.internal.fingerprint.classpath.impl.ClasspathFingerprintingStrategy.runtimeClasspath
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import sun.jvmstat.monitor.MonitoredVmUtil.mainClass
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.jar.JarFile

val appVersion = libs.versions.app.get()
val javaVersion = libs.versions.java.get()
val javaVersionInt = javaVersion.toInt()
val jvmTargetVersion = JvmTarget.fromTarget(javaVersion)
project.version = appVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.compose.hot-reload") version "1.3.0-beta01"
    alias(libs.plugins.compose)
    id("org.gradlex.extra-java-module-info") version "1.14.2"
    idea
}

apply(from = "../gradle/target-platform.gradle.kts")
val rdiTargetPlatform: String by extra
val lwjglNatives: String by extra
val zstdClassifier: String by extra

extraJavaModuleInfo {
    failOnMissingModuleInfo.set(false)
    skipLocalJars.set(true)

    module("org.bytedeco:javacv", "org.bytedeco.javacv") {
        patchRealModule()
        exportAllPackages()
        requires("java.desktop")
        requiresTransitive("org.bytedeco.javacpp")
        requires("org.bytedeco.ffmpeg")
    }
}

configurations.configureEach {
    if (name.startsWith("composeHotReloadDev")) {
        attributes.attribute(
            ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE,
            ArtifactTypeDefinition.JAR_TYPE
        )
        extraJavaModuleInfo.activate(this)
    }
}

tasks.named<Wrapper>("wrapper") {
    distributionType = Wrapper.DistributionType.BIN
}

repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven(url = "https://jitpack.io")
}

base {
    archivesName.set("rdi-ui")
}

kotlin {
    jvmToolchain(javaVersionInt)
    compilerOptions {
        jvmTarget.set(jvmTargetVersion)
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
    sourceSets.all {
        languageSettings.optIn("androidx.compose.material3.ExperimentalMaterial3Api")
        languageSettings.optIn("androidx.compose.ui.ExperimentalComposeUiApi")
    }
}

dependencies {
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$rdiTargetPlatform:${libs.versions.compose.plugin.get()}")
    implementation(kotlin("reflect"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.desktop)
    implementation(libs.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.haze)
    implementation(libs.haze.glass)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.joml)
    /* RMCP运行库暂时继续由各个MinecraftMod使用Jar-in-Jar提供。
    implementation("io.heapy.kotaml:kotaml-jvm:0.111.0")
    implementation("com.squareup.okio:okio-jvm:3.18.2")
    implementation("it.krzeminski:snakeyaml-engine-kmp-jvm:4.0.1")
    implementation("net.thauvin.erik.urlencoder:urlencoder-lib-jvm:1.6.0")
    implementation("io.fusionauth:java-http:1.4.0")
    */
    implementation(libs.minecraft.auth)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.oshi.core.desktop)
    implementation(libs.logback.classic)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.bundles.ktor.common)
    implementation(libs.ktor.client.auth)
    implementation(libs.bundles.ktor.json.client)
    implementation(libs.jsoup)
    implementation(libs.bundles.mongodb)
    implementation(libs.caffeine)
    implementation(libs.maven.artifact)
    implementation(libs.zstd.jni)

    implementation(project(":assets"))
    implementation(project(":theme"))
    implementation(project(":code-editor"))
    implementation(project(":code-editor-ui"))
    implementation(project(":database"))
    implementation(project(":client-store"))
    implementation(project(":misc"))
    implementation(project(":model"))
    implementation(project(":net"))
    implementation(project(":archive"))
    implementation(project(":anvilrw"))
    implementation(project(":blessing-skin"))
    implementation(project(":mediaproc"))
    implementation(project(":pack-proc"))
    implementation(project(":mclaunch"))
    implementation(project(":mc-install"))
    implementation(project(":modpack-test"))
    implementation(project(":player-info"))
    implementation(project(":player-model"))
    implementation(project(":player-model-core"))
    implementation(project(":bg-renderer"))
    implementation(project(":render-core"))
    implementation(project(":mod-catalog"))
   // implementation(project(":webview2"))
    implementation(project(":forgeguard"))
    implementation(project(":local-mc-proxy"))

    val lwjglVersion = libs.versions.lwjgl.get()
    listOf("", "glfw").forEach { component ->
        val suffix = component.takeIf(String::isNotEmpty)?.let { "-$it" }.orEmpty()
        implementation("org.lwjgl:lwjgl${suffix}:$lwjglVersion")
        implementation("org.lwjgl:lwjgl${suffix}:$lwjglVersion:$lwjglNatives")
    }

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
}

compose.desktop {
    application {
        mainClass = "calebxzau.rdi.client.MainKt"
    }
}

allprojects {
    configurations.all {
        resolutionStrategy {
            force("org.slf4j:slf4j-api:${libs.versions.slf4j.api.get()}")
            // The default zstd-jni jar bundles natives for every platform; ship only the target platform's.
            dependencySubstitution {
                substitute(module("com.github.luben:zstd-jni"))
                    .using(module("com.github.luben:zstd-jni:${libs.versions.zstd.get()}"))
                    .withClassifier(zstdClassifier)
            }
        }
    }
}

configurations.configureEach {
    resolutionStrategy {
        force("com.ibm.icu:icu4j:${libs.versions.icu4j.get()}")
        force("it.unimi.dsi:fastutil:${libs.versions.fastutil.get()}")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (!name.endsWith("JavaWithJavac")) {
        options.release.set(javaVersionInt)
    }
}

tasks.withType<Jar>().configureEach {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version.toString()
        )
    }
}

val runDir = layout.projectDirectory.dir("run").asFile

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    workingDir = runDir
    doFirst {
        workingDir.mkdirs()
    }
}

val localModpackTest by sourceSets.creating {
    kotlin.srcDir("src/test/kotlin")
    kotlin.include(
        "calebxzhou/rdi/client/service/ModpackArchiveReaderTest.kt",
        "calebxzau/rdi/client/service/LocalModpackScannerTest.kt",
        "calebxzau/rdi/client/service/ModpackUploadRuntimeGuardTest.kt",
    )
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

kotlin.target.compilations.getByName("localModpackTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

configurations[localModpackTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[localModpackTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("localModpackTest") {
    testClassesDirs = localModpackTest.output.classesDirs
    classpath = localModpackTest.runtimeClasspath
    useJUnitPlatform()
}

// Removed custom upToDateWhen — it was breaking Kotlin's incremental compilation,
// causing full recompilation on every hot reload instead of only changed files.



idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val hotRunBaseJvmArgs = listOf(
    "-Xmx2G",
    // A larger initial heap avoids an early GC that can crash the hot reload agent at startup
    // (ClassCircularityError on jdk/internal/vm/ContinuationSupport under JDK 25).
    "-Xms1024m",
    // JBR's java.io-over-NIO records failed lookups in a WeakHashMap; cleaning it inside the
    // hot reload agent's class transformer can hang startup before any window appears.
    "-Djbr.java.io.use.nio=false",
    "-Drdi.debug=true",
    "-Drdi.noHttps=true",
   // "-Drdi.noUpdate=true",
    //"-Drdi.netMetrics=true",
   // "-Dskiko.renderApi=OPENGL",
    "-Drdi.account=eyJfaWQiOiI2OGIzMTRiYmFkYWY1MmRkYWI5NmI1ZWQiLCJuYW1lIjoiMTIzMTIzIiwicHdkIjoiMTIzQEBAIiwicXEiOiIxMjMxMjMifQ=="
)

tasks.withType<JavaExec>().configureEach {
    workingDir = runDir
    doFirst {
        runDir.mkdirs()
    }
}

tasks.matching { it.name == "hotRun" || it.name == "hotDev" }.configureEach {
    notCompatibleWithConfigurationCache("uses project file operations at execution time")
    if (this is JavaExec) {
        systemProperties = System.getProperties().filter { it.key.toString().startsWith("rdi.") } as MutableMap<String, Any?>
        jvmArgs(hotRunBaseJvmArgs)
    }
}

evaluationDependsOn(":early-display")
val earlyDisplayJar = project(":early-display").tasks.named<Jar>("jar")

val runtimeArtifactNames = providers.provider<Map<File, String>> {
    configurations.getByName("runtimeClasspath").incoming.artifacts.resolvedArtifacts.get()
        .groupBy { it.file.name }
        .flatMap { (fileName, artifacts) ->
            val isDuplicate = artifacts.size > 1
            artifacts.map { artifact ->
                val component = artifact.id.componentIdentifier
                val outputName = if (
                    isDuplicate &&
                    component is ModuleComponentIdentifier &&
                    component.group.startsWith("androidx.")
                ) {
                    "androidx-$fileName"
                } else {
                    fileName
                }
                artifact.file to outputName
            }
        }
        .toMap()
}

val baseWorldTest by sourceSets.creating {
    kotlin.srcDir("src/test/kotlin")
    kotlin.include("calebxzau/rdi/client/**/BaseWorld*Test.kt")
    kotlin.include("calebxzau/rdi/client/**/ChunkedUploaderTest.kt")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[baseWorldTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[baseWorldTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("baseWorldTest") {
    testClassesDirs = baseWorldTest.output.classesDirs
    classpath = baseWorldTest.runtimeClasspath
    useJUnitPlatform()
}

val modpackUploadTest by sourceSets.creating {
    kotlin.srcDir("src/test/kotlin")
    kotlin.include("calebxzau/rdi/client/service/ModpackChunkedUploaderTest.kt")
    kotlin.include("calebxzau/rdi/client/service/ModpackUploadApiTest.kt")
    kotlin.include("calebxzau/rdi/client/service/ModpackClientExtrasInstallTest.kt")
    kotlin.include("calebxzau/rdi/client/ui/viewmodel/ModpackUploadViewModelTest.kt")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

kotlin.target.compilations.getByName("modpackUploadTest")
    .associateWith(kotlin.target.compilations.getByName("main"))

configurations[modpackUploadTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[modpackUploadTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("modpackUploadTest") {
    testClassesDirs = modpackUploadTest.output.classesDirs
    classpath = modpackUploadTest.runtimeClasspath
    useJUnitPlatform()
}

// One UI release carries a manifest per platform. This build resolves natives for rdiTargetPlatform;
// the other platforms are staged by nested builds with -Prdi.targetPlatform.
val uiReleasePlatforms = listOf("windows-x64", "linux-x64", "macos-arm64")

// File name markers of each platform's native jars, checked independently of target-platform.gradle.kts.
val uiPlatformNativeMarkers = mapOf(
    "windows-x64" to listOf(
        "skiko-awt-runtime-windows-x64-", "-natives-windows.jar", "-windows-x86_64-gpl.jar", "-win_amd64.jar",
        "rdi-sqlite-natives-windows-x64.jar",
    ),
    "linux-x64" to listOf(
        "skiko-awt-runtime-linux-x64-", "-natives-linux.jar", "-linux-x86_64-gpl.jar", "-linux_amd64.jar",
        "rdi-sqlite-natives-linux-x64.jar",
    ),
    "macos-arm64" to listOf(
        "skiko-awt-runtime-macos-arm64-", "-natives-macos-arm64.jar", "-macosx-arm64-gpl.jar", "-darwin_aarch64.jar",
        "rdi-sqlite-natives-macos-arm64.jar",
    ),
)

fun uiInstallLibDir(platform: String) = layout.buildDirectory.dir("install/ui/$platform/lib")

tasks.register<Sync>("desktopInstallLibs") {
    duplicatesStrategy = DuplicatesStrategy.FAIL
    notCompatibleWithConfigurationCache("resolves runtime artifact metadata while staging libraries")
    dependsOn("jar", earlyDisplayJar)
    from(configurations.runtimeClasspath) {
        eachFile {
            name = runtimeArtifactNames.get()[file] ?: name
        }
    }
    from(tasks.named<Jar>("jar"))
    from(earlyDisplayJar)
    into(uiInstallLibDir(rdiTargetPlatform))
}

// Nested builds share build directories with this one, so they run one at a time after it has staged
// its own platform; running in parallel races on compiled classes.
val nestedDesktopInstallLibs = uiReleasePlatforms.filter { it != rdiTargetPlatform }
    .fold(emptyList<TaskProvider<GradleBuild>>()) { registered, platform ->
        registered + tasks.register<GradleBuild>("desktopInstallLibs-$platform") {
            notCompatibleWithConfigurationCache("runs a nested build for another target platform")
            group = "distribution"
            description = "Stage the UI runtime libraries for $platform."
            setTasks(listOf("desktopInstallLibs"))
            // Each nested build needs its own build path, or the second one clashes with the first as `:ui`.
            buildName = "ui-$platform"
            startParameter.projectProperties = mapOf("rdi.targetPlatform" to platform)
            mustRunAfter("desktopInstallLibs")
            registered.lastOrNull()?.let { mustRunAfter(it) }
        }
    }

tasks.named<Jar>("jar") {
    archiveFileName.set("rdi-ui.jar")
}


val uiReleasesRootDir = layout.buildDirectory.dir("ui-releases")
// Fixed ZIP entry time keeps release.zip byte-identical across rebuilds of the same files.
val UI_RELEASE_ENTRY_TIME = 315532800000L
val uiReleaseOutputDir = layout.buildDirectory.dir("ui-releases/$appVersion")

fun jsonQuote(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

fun sha1(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun sha1(file: File): String = file.inputStream().use { sha1(it) }

fun readUiManifest(file: File): Map<String, String> {
    val parsed = JsonSlurper().parse(file) as? Map<*, *>
        ?: throw GradleException("UI库manifest不是JSON对象: $file")
    return parsed.entries.associate { entry ->
        val name = entry.key as? String
            ?: throw GradleException("UI库manifest包含非法文件名: $file")
        val hash = entry.value as? String
            ?: throw GradleException("UI库manifest包含非法hash: $file")
        name to hash
    }
}

fun validateUiReleaseArchive(archive: File, manifests: Map<String, Map<String, String>>) {
    ZipFile(archive).use { zip ->
        val entries = mutableListOf<ZipEntry>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) entries += enumeration.nextElement()

        if (entries.isEmpty() || entries.none { it.name.endsWith(".jar", ignoreCase = true) }) {
            throw GradleException("UI库ZIP缺少JAR: $archive")
        }
        if (entries.any { entry ->
                entry.isDirectory || entry.name.isBlank() || entry.name == "." || entry.name == ".." ||
                    entry.name.contains('/') || entry.name.contains('\\') || entry.name.contains(':')
            }) {
            throw GradleException("UI库ZIP包含非法entry: $archive")
        }
        val entryNames = entries.map { it.name }
        val expectedNames = manifests.values.flatMap { it.keys }.toSet()
        if (entryNames.size != entryNames.toSet().size || entryNames.toSet() != expectedNames) {
            throw GradleException("UI库ZIP和各平台manifest的文件并集不一致: $archive")
        }
        val actualHashes = entries.associate { entry -> entry.name to zip.getInputStream(entry).use { sha1(it) } }
        manifests.forEach { (platform, manifest) ->
            manifest.forEach { (name, hash) ->
                if (!hash.equals(actualHashes[name], ignoreCase = true)) {
                    throw GradleException("UI库ZIP文件hash与${platform}的manifest不匹配: $name")
                }
            }
        }
    }
}

// Same name must mean same bytes on every platform, because all platforms share one release.zip.
fun mergeUiReleaseFiles(platformFiles: Map<String, Map<String, File>>): Map<String, File> {
    val merged = linkedMapOf<String, File>()
    val mergedHashes = mutableMapOf<String, String>()
    val conflicts = sortedSetOf<String>()
    platformFiles.forEach { (_, files) ->
        files.forEach { (name, file) ->
            val hash = sha1(file)
            val existing = mergedHashes.putIfAbsent(name, hash)
            if (existing == null) {
                merged[name] = file
            } else if (existing != hash) {
                conflicts += name
            }
        }
    }
    if (conflicts.isNotEmpty()) {
        throw GradleException("以下文件在不同平台的内容不同，无法共用release.zip: ${conflicts.joinToString()}")
    }
    return merged.toSortedMap()
}

// STORED keeps jars uncompressed (already compressed), so the server can stream entries cheaply.
fun writeStoredZip(archive: File, files: Map<String, File>) {
    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
        zip.setMethod(ZipOutputStream.STORED)
        files.forEach { (name, file) ->
            val checksum = CRC32()
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    checksum.update(buffer, 0, read)
                }
            }
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = file.length()
                compressedSize = file.length()
                crc = checksum.value
                time = UI_RELEASE_ENTRY_TIME
            }
            zip.putNextEntry(entry)
            file.inputStream().buffered().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

// Each platform ships its own natives only: every marker of its platform, none of the others'.
fun validateUiPlatformNatives(platform: String, fileNames: Set<String>) {
    val ownMarkers = uiPlatformNativeMarkers[platform] ?: throw GradleException("未知平台: $platform")
    val missing = ownMarkers.filter { marker -> fileNames.none { it.contains(marker) } }
    if (missing.isNotEmpty()) throw GradleException("${platform}缺少原生库: ${missing.joinToString()}")
    val foreign = uiPlatformNativeMarkers.filterKeys { it != platform }.values.flatten()
        .flatMap { marker -> fileNames.filter { it.contains(marker) } }
        .distinct()
    if (foreign.isNotEmpty()) throw GradleException("${platform}混入了其他平台的原生库: ${foreign.joinToString()}")
}

val packageUiRelease = tasks.register("packageUiRelease") {
    dependsOn("desktopInstallLibs", nestedDesktopInstallLibs)
    notCompatibleWithConfigurationCache("reads staged libraries and validates the generated ZIP")
    group = "distribution"
    description = "Generate the versioned UI library ZIP, per-platform manifests and latest pointer."

    doLast {
        val platformFiles = uiReleasePlatforms.associateWith { platform ->
            val sourceDir = uiInstallLibDir(platform).get().asFile
            if (!sourceDir.isDirectory) throw GradleException("未找到${platform}的UI运行库目录: $sourceDir")
            val files = sourceDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                .orEmpty()
            if (files.isEmpty() || files.none { it.name.endsWith(".jar", ignoreCase = true) }) {
                throw GradleException("${platform}的UI运行库目录缺少JAR: $sourceDir")
            }
            val uiJar = sourceDir.resolve("rdi-ui.jar")
            if (!uiJar.isFile) throw GradleException("${platform}的UI运行库目录缺少rdi-ui.jar: $sourceDir")
            val implementationVersion = JarFile(uiJar).use {
                it.manifest?.mainAttributes?.getValue("Implementation-Version")
            }
            if (implementationVersion != appVersion) {
                throw GradleException(
                    "${platform}的rdi-ui.jar的Implementation-Version不匹配，期望$appVersion，实际${implementationVersion ?: "未知"}"
                )
            }
            files.associateBy { it.name }
        }
        platformFiles.forEach { (platform, files) -> validateUiPlatformNatives(platform, files.keys) }
        val mergedFiles = mergeUiReleaseFiles(platformFiles)

        val rootDir = uiReleasesRootDir.get().asFile
        val outputDir = uiReleaseOutputDir.get().asFile
        if (outputDir.exists() && !outputDir.deleteRecursively()) {
            throw GradleException("无法清理旧的UI发布目录: $outputDir")
        }
        outputDir.mkdirs()

        val manifests = platformFiles.mapValues { (platform, files) ->
            val hashes = files.mapValues { (_, file) -> sha1(file) }.toSortedMap()
            val manifest = outputDir.resolve("$platform.json")
            manifest.writeText(
                hashes.entries.joinToString(",\n", "{\n", "\n}\n") { (name, hash) ->
                    "    ${jsonQuote(name)}: ${jsonQuote(hash)}"
                }
            )
            val parsedHashes = readUiManifest(manifest)
            if (parsedHashes != hashes) throw GradleException("UI库manifest内容校验失败: $manifest")
            parsedHashes
        }

        val archive = outputDir.resolve("release.zip")
        writeStoredZip(archive, mergedFiles)
        validateUiReleaseArchive(archive, manifests)
        rootDir.resolve("latest.txt").writeText("$appVersion\n")
        logger.lifecycle("已生成UI库发布包: $outputDir（平台: ${uiReleasePlatforms.joinToString()}）")
    }
}


// Files.copy uses the OS copy (CopyFileExW on Windows). Gradle's Copy/Sync and Kotlin's File.copyTo
// write 8KB at a time, which crawls at ~20Mbps over the \\rdi6 SMB share.
fun publishFile(source: File, target: File) {
    if (!source.isFile) throw GradleException("未找到发布文件: $source")
    target.parentFile.mkdirs()
    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

val repoRootDir = file("../..")

fun registerCopyTask(name: String, extraDestinationRoots: List<String> = emptyList()) {
    val baseDestinationRoots = listOf(
        file("../../server/master/run/client-libs"),
        //     File(System.getProperty("user.home"), "Documents/rdi5ship/lib")
    )
    val destinationRoots = baseDestinationRoots + extraDestinationRoots.map { file(it) }

    tasks.register(name) {
        group = "distribution"
        dependsOn(packageUiRelease)
        notCompatibleWithConfigurationCache("copies the UI release at execution time")
        doLast {
            val releaseFiles = uiReleaseOutputDir.get().asFile.listFiles()?.filter { it.isFile }.orEmpty()
            if (releaseFiles.isEmpty()) throw GradleException("UI发布包为空: ${uiReleaseOutputDir.get().asFile}")
            val latestFile = uiReleasesRootDir.get().asFile.resolve("latest.txt")
            val archiveId = "ui-release-${System.currentTimeMillis()}"
            destinationRoots.forEachIndexed { index, destinationRoot ->
                val uiDir = destinationRoot.resolve("ui")
                val versionDir = uiDir.resolve(appVersion)
                // Like the old Sync: files no longer in this version leave the version dir, archived under DEL/.
                val releaseNames = releaseFiles.map { it.name }.toSet()
                versionDir.listFiles()?.filter { it.name !in releaseNames }?.forEach { stale ->
                    val archived = repoRootDir.resolve("DEL/$archiveId/client-libs-$index/ui/$appVersion/${stale.name}")
                    archived.parentFile.mkdirs()
                    Files.move(stale.toPath(), archived.toPath())
                    logger.lifecycle("已归档旧文件$stale 到$archived")
                }
                releaseFiles.forEach { publishFile(it, versionDir.resolve(it.name)) }
                // latest.txt goes last, so the server never points at a half-copied version.
                publishFile(latestFile, uiDir.resolve(latestFile.name))
                logger.lifecycle("已发布UI库${appVersion}到$uiDir")
            }
        }
    }
}

registerCopyTask("出core2-local")
registerCopyTask("出core2-release", listOf("\\\\rdi6\\rdi\\master\\client-libs"))

val nixScriptDir = layout.projectDirectory.dir("nix")

fun requireLfScript(script: File) {
    if (!script.isFile) throw GradleException("未找到启动脚本: $script")
    if (script.readBytes().contains('\r'.code.toByte())) throw GradleException("${script.name}必须使用LF换行")
}

// The master serves client-libs/scripts/rdi.sh to rdi.sh's self-update for every non-Windows platform.
fun registerNixScriptCopyTask(name: String, destinationRoots: List<File>) {
    tasks.register(name) {
        notCompatibleWithConfigurationCache("copies the launcher script at execution time")
        group = "distribution"
        description = "Publish rdi.sh for the Linux/macOS self-update route."
        doLast {
            val script = nixScriptDir.file("rdi.sh").asFile
            requireLfScript(script)
            destinationRoots.forEach { root ->
                val scriptsDir = root.resolve("scripts").also { it.mkdirs() }
                publishFile(script, scriptsDir.resolve("rdi.sh"))
                logger.lifecycle("已发布rdi.sh到$scriptsDir")
            }
        }
    }
}

registerNixScriptCopyTask("出rdi-sh-local", listOf(file("../../server/master/run/client-libs")))
registerNixScriptCopyTask(
    "出rdi-sh-release",
    listOf(file("../../server/master/run/client-libs"), file("\\\\rdi6\\rdi\\master\\client-libs"))
)

// The pack ships the same lib/ the release manifests were made from, so the first run finds it up to date;
// rdi.sh then only fetches files changed by later releases.
val nixShipPlatforms = uiReleasePlatforms.filter { it != "windows-x64" }
val nixShipPackTasks = nixShipPlatforms.map { platform ->
    tasks.register<Tar>("makeNixShipPack-$platform") {
        dependsOn(packageUiRelease)
        group = "distribution"
        description = "Create rdi-$platform.tar.gz with rdi.sh, the guide and the UI libraries."
        notCompatibleWithConfigurationCache("checks the launcher script with a build script function")
        // macOS players can double-click RDI.command instead of typing sh rdi.sh in Terminal.
        val scripts = if (platform == "macos-arm64") listOf("rdi.sh", "RDI.command") else listOf("rdi.sh")
        doFirst { scripts.forEach { requireLfScript(nixScriptDir.file(it).asFile) } }
        archiveFileName.set("rdi-$platform.tar.gz")
        destinationDirectory.set(layout.buildDirectory.dir("nix-ship"))
        compression = Compression.GZIP
        into("rdi") {
            from(nixScriptDir) {
                include(scripts)
                filePermissions { unix("rwxr-xr-x") }
            }
            from(nixScriptDir) {
                include("README.txt")
                filePermissions { unix("rw-r--r--") }
            }
        }
        into("rdi/lib") {
            from(uiInstallLibDir(platform))
            filePermissions { unix("rw-r--r--") }
        }
    }
}

tasks.register("makeNixShipPack") {
    group = "distribution"
    description = "Create the Linux/macOS shipping archives."
    dependsOn(nixShipPackTasks)
}
val cargoReleaseCmd = listOf(
    "cargo",
    "build",
    "--release",
    "--locked",
)
fun registerUpdaterTask(name: String, destinationDirs: List<File>) {

    tasks.register(name) {
        notCompatibleWithConfigurationCache("invokes cargo and zstd, then copies the updater")
        group = "distribution"
        description = "Build, compress and publish the Windows updater."

        val updaterDir = layout.projectDirectory.dir("../updater").asFile
        val updaterExe = updaterDir.resolve("target/release/updater.exe")
        val compressedUpdater = updaterExe.parentFile.resolve("updater.exe.zst")
        val updaterSha1File = updaterExe.parentFile.resolve("updater.exe.sha1")

        doLast {
            fun runProcess(command: List<String>, workingDirectory: File? = null): Int {
                logger.lifecycle("> ${command.joinToString(" ")}")
                val process = ProcessBuilder(command).apply {
                    workingDirectory?.let { directory(it) }
                }.start()
                val stdoutThread = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { logger.lifecycle("[stdout] $it") }
                    }
                }.apply { start() }
                val stderrThread = Thread {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { logger.lifecycle("[stderr] $it") }
                    }
                }.apply { start() }
                val exitCode = process.waitFor()
                stdoutThread.join()
                stderrThread.join()
                return exitCode
            }

            val buildExitCode = runProcess(
                cargoReleaseCmd,
                updaterDir
            )
            if (buildExitCode != 0) {
                throw GradleException("cargo build updater失败，退出码: $buildExitCode")
            }

            if (!updaterExe.isFile) {
                throw GradleException("未找到updater: $updaterExe")
            }

            val updaterSha1 = MessageDigest.getInstance("SHA-1")
                .digest(updaterExe.readBytes())
                .joinToString("") { "%02x".format(it) }
            updaterSha1File.writeText(updaterSha1)

            val compressExitCode = runProcess(
                listOf(
                    "zstd",
                    "-22",
                    "-f",
                    updaterExe.absolutePath,
                    "-o",
                    compressedUpdater.absolutePath,
                )
            )
            if (compressExitCode != 0) {
                throw GradleException("zstd压缩updater失败，退出码: $compressExitCode")
            }

            destinationDirs.forEach { destinationDir ->
                destinationDir.mkdirs()
                publishFile(compressedUpdater, destinationDir.resolve(compressedUpdater.name))
                publishFile(updaterSha1File, destinationDir.resolve(updaterSha1File.name))
                logger.lifecycle("已发布updater到$destinationDir")
            }
        }
    }
}

val localUpdaterDestination = file("../../server/master/run/client-libs/updaters")
registerUpdaterTask("出updater-local", listOf(localUpdaterDestination))
registerUpdaterTask(
    "出updater-release",
    listOf(
        localUpdaterDestination,
        file("\\\\rdi6\\rdi\\master\\client-libs\\updaters"),
    )
)
/*

tasks.register("makeShipPack") {
    notCompatibleWithConfigurationCache("uses project file operations and external process execution at execution time")
    val shipDir = File(System.getProperty("user.home"), "Documents/rdi5ship")
    val filesNeed = listOf("lib", "启动.exe", "备用启动.exe","fonts")
    val archiveFile = File(shipDir, "rdi-${version}.7z")
    group = "distribution"
    description = "Create shipping 7z archive in Documents/rdi5ship with LZMA2 multi-thread compression."

    doFirst {
        if (!shipDir.exists()) throw GradleException("未找到 ship 目录: $shipDir")
        filesNeed.forEach { name ->
            val target = File(shipDir, name)
            if (!target.exists()) throw GradleException("缺少文件或目录: $target")
        }
    }
    doLast {
        val sevenZipExecutable = sequenceOf(
            File("C:/Program Files/7-Zip/7z.exe"),
            File("C:/Program Files (x86)/7-Zip/7z.exe")
        ).firstOrNull { it.exists() && it.isFile }?.absolutePath ?: "7z"

        if (archiveFile.exists() && !archiveFile.delete()) {
            throw GradleException("无法删除旧压缩包: $archiveFile")
        }

        val process = ProcessBuilder(
            sevenZipExecutable,
            "a",
            "-t7z",
            "-m0=lzma2",
            "-mx=9",
            "-mmt=on",
            archiveFile.absolutePath,
            "lib",
            "fonts",
            "启动.exe",
            "备用启动.exe",
        ).directory(shipDir)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException("7z打包失败，退出码: $exitCode")
        }
    }
}
*/

tasks.register("makeShipPackZst") {
    notCompatibleWithConfigurationCache("uses project file operations and external process execution at execution time")
    val shipDir = File(System.getProperty("user.home"), "Documents/rdi5ship")
    val filesNeed = listOf( "lib", "start.exe")
    val temporaryTar = layout.buildDirectory.file("installer/assets/client.tar").get().asFile
    val archiveFile = layout.projectDirectory.file("../installer/assets/client.tar.zst").asFile
    group = "distribution"
    description = "Create the installer client.tar.zst from Documents/rdi5ship."

    doLast {
        filesNeed.forEach { name ->
            val target = shipDir.resolve(name)
            if (!target.exists()) throw GradleException("缺少文件或目录:$target")
        }

        temporaryTar.parentFile.mkdirs()
        archiveFile.parentFile.mkdirs()
        try {
            val tarExitCode = ProcessBuilder(
                "tar",
                "-cf",
                temporaryTar.absolutePath,
                "-C",
                shipDir.absolutePath,
                *filesNeed.toTypedArray(),
            ).inheritIO()
                .start()
                .waitFor()
            if (tarExitCode != 0) throw GradleException("tar打包失败，退出码:$tarExitCode")

            val zstdExitCode = ProcessBuilder(
                "zstd",
                "-T0",
                "-22",
                "-f",
                temporaryTar.absolutePath,
                "-o",
                archiveFile.absolutePath,
            ).inheritIO()
                .start()
                .waitFor()
            if (zstdExitCode != 0) throw GradleException("zstd压缩失败，退出码:$zstdExitCode")
        } finally {
            temporaryTar.delete()
        }
    }
}

tasks.register("出install") {
    dependsOn("makeShipPackZst")
    notCompatibleWithConfigurationCache("invokes cargo in the installer directory")
    group = "distribution"
    description = "Create the shipping pack and publish the Windows installer."

    val installerDir = layout.projectDirectory.dir("../installer").asFile
    val installerExe = installerDir.resolve("target/release/rdi-installer.exe")

    doLast {
        val exitCode = ProcessBuilder(cargoReleaseCmd)
            .directory(installerDir)
            .inheritIO()
            .start()
            .waitFor()
        if (exitCode != 0) throw GradleException("cargo build installer失败，退出码:$exitCode")
        if (!installerExe.isFile) throw GradleException("未找到installer:$installerExe")
    }
}


