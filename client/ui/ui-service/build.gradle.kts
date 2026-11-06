import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
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
    implementation(project(":ui-logic"))
    implementation("androidx.lifecycle:lifecycle-viewmodel-desktop:${libs.versions.androidx.lifecycle.get()}")
    runtimeOnly(libs.logback.classic)
    runtimeOnly(project(":assets"))
    testImplementation(kotlin("test"))
}
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    workingDir = rootProject.projectDir
}
application { mainClass.set("calebxzau.rdi.service.MainKt") }
// JetBrains' relocation artifact and AndroidX implementation have the same filename.
// Keep the implementation, not the metadata-only relocation jar, in the distribution.
configurations.configureEach {
    exclude(group = "org.jetbrains.androidx.lifecycle", module = "lifecycle-viewmodel-desktop")
}
