import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

apply(from = "../gradle/target-platform.gradle.kts")
val lwjglNatives: String by extra
val rdiTargetPlatform: String by extra

//project.version = libs.versions.app.get()

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
}

dependencies {
    api(compose.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.joml)
    implementation(libs.kotlin.logging.jvm)
    implementation(project(":render-core"))
    implementation(project(":player-model-core"))

    val lwjglVersion = libs.versions.lwjgl.get()
    listOf("", "glfw", "opengl").forEach { component ->
        val suffix = component.takeIf(String::isNotEmpty)?.let { "-${it}" }.orEmpty()
        implementation("org.lwjgl:lwjgl${suffix}:${lwjglVersion}")
        implementation("org.lwjgl:lwjgl${suffix}:${lwjglVersion}:$lwjglNatives")
    }

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.logback.classic)
    // The preview exercises the production Skia bitmap pool as well as OpenGL rendering.
    testRuntimeOnly("org.jetbrains.compose.desktop:desktop-jvm-$rdiTargetPlatform:${libs.versions.compose.plugin.get()}")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Dev-only GPU check; needs a desktop GPU, so it is not part of `test`.
// Optional: -PpreviewTimes=0,15,45 selects the elapsed seconds of the written frames.
tasks.register<JavaExec>("renderPreview") {
    description = "Renders background frames on the local GPU into build/bg-preview and prints frame timings."
    group = "verification"
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("calebxzau.rdi.bgrenderer.BackgroundPreviewKt")
    args(layout.buildDirectory.dir("bg-preview").get().asFile.absolutePath)
    (project.findProperty("previewTimes") as String?)?.split(',')?.let { args(it) }
}

base {
    archivesName.set("rdi-bg-renderer")
}
