import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
repositories { mavenCentral(); google(); mavenLocal() }
kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}
dependencies {
    api(project(":client-core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.androidx.lifecycle.viewmodel)
    testImplementation(kotlin("test"))
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
}
