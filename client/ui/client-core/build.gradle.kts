import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

// Launcher services shared by the desktop UI. Compose stays out of this module on purpose.
dependencies {
    api(project(":misc"))
    api(project(":model"))
    api(project(":net"))
    api(project(":archive"))
    api(project(":anvilrw"))
    api(project(":database"))
    api(project(":client-store"))
    api(project(":mod-catalog"))
    api(project(":pack-proc"))
    api(project(":mclaunch"))
    api(project(":mc-install"))
    api(project(":modpack-test"))
    api(project(":player-info"))
    api(project(":blessing-skin"))
    api(project(":mediaproc"))
    api(project(":local-mc-proxy"))

    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.bundles.ktor.common)
    api(libs.bundles.ktor.json.client)
    api(libs.bundles.mongodb)
    api(libs.minecraft.auth)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.auth)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.oshi.core.desktop)
    implementation(libs.zstd.jni)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
}

base {
    archivesName.set("rdi-client-core")
}

tasks.named<Jar>("jar") {
    archiveFileName.set("rdi-client-core.jar")
    // Const.VERSION_NUMBER reads Implementation-Version from the jar that defines its package.
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to libs.versions.app.get()
        )
    }
}

val runDir = rootProject.layout.projectDirectory.dir("run").asFile

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    workingDir = runDir
    doFirst {
        workingDir.mkdirs()
    }
}

// Focused test tasks compile only their own tests, so an unrelated broken test cannot block them.
// The UI module registers tasks with the same names for its ViewModel tests.
fun registerFocusedTest(name: String, vararg includes: String) {
    val focused = sourceSets.create(name) {
        kotlin.srcDir("src/test/kotlin")
        includes.forEach { kotlin.include(it) }
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
    kotlin.target.compilations.getByName(name)
        .associateWith(kotlin.target.compilations.getByName("main"))
    configurations[focused.implementationConfigurationName]
        .extendsFrom(configurations.testImplementation.get())
    configurations[focused.runtimeOnlyConfigurationName]
        .extendsFrom(configurations.testRuntimeOnly.get())
    tasks.register<Test>(name) {
        testClassesDirs = focused.output.classesDirs
        classpath = focused.runtimeClasspath
    }
}

registerFocusedTest(
    "localModpackTest",
    "calebxzhou/rdi/client/service/ModpackArchiveReaderTest.kt",
    "calebxzau/rdi/client/service/LocalModpackScannerTest.kt",
    "calebxzau/rdi/client/service/ModpackUploadRuntimeGuardTest.kt",
)
registerFocusedTest(
    "baseWorldTest",
    "calebxzau/rdi/client/service/BaseWorld*Test.kt",
    "calebxzau/rdi/client/service/ChunkedUploaderTest.kt",
)
registerFocusedTest(
    "modpackUploadTest",
    "calebxzau/rdi/client/service/ModpackChunkedUploaderTest.kt",
    "calebxzau/rdi/client/service/ModpackUploadApiTest.kt",
    "calebxzau/rdi/client/service/ModpackClientExtrasInstallTest.kt",
)
