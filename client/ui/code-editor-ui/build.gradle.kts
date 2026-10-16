import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

apply(from = "../../gradle/target-platform.gradle.kts")
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
    api(compose.runtime)
    api(compose.ui)
    api(project(":code-editor"))
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$rdiTargetPlatform:${libs.versions.compose.plugin.get()}")
    implementation(compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":theme"))
}

base {
    archivesName.set("rdi-code-editor-ui")
}
