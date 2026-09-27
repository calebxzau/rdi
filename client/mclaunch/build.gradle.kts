import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get()

repositories {
    mavenLocal()
    mavenCentral()
}

kotlin {
    jvmToolchain(javaVersion.toInt())
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion))
}

dependencies {
    implementation(project(":misc"))
    implementation(project(":model"))
    implementation(project(":mediaproc"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging.jvm)

    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.zstd.jni)
    testRuntimeOnly(libs.logback.classic)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
base {
    archivesName.set("rdi-mc-launch")
}
