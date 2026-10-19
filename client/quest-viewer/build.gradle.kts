import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

apply(from = "../gradle/target-platform.gradle.kts")
val rdiTargetPlatform = extra["rdiTargetPlatform"] as String

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
}

dependencies {
    api(libs.compose.runtime)
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$rdiTargetPlatform:${libs.versions.compose.plugin.get()}")
    implementation(project(":quests"))
    implementation(project(":theme"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
    testRuntimeOnly(libs.logback.classic)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val samples = providers.environmentVariable("RDI_QUEST_VIEWER_SAMPLES").orElse("")
    inputs.property("questViewerSamples", samples)
    inputs.files(samples.map { paths ->
        paths.split(File.pathSeparator).filter(String::isNotBlank).flatMap { directory ->
            listOf(File(directory, "config/ftbquests/quests"), File(directory, "rdi/preview"))
        }
    }).withPropertyName("questViewerSampleFiles").withPathSensitivity(PathSensitivity.RELATIVE)
}
base { archivesName.set("rdi-quest-viewer") }
