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

// J5b (J5 decision D4): the jars a room game needs, for the Rust launcher. Development builds read
// build/game-runtime; packaging it is J6.
val forgeguardAgent = configurations.create("forgeguardAgent") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies { forgeguardAgent(project(":forgeguard")) }
tasks.register<JavaExec>("gameRuntime") {
    group = "rdi"
    description = "Writes build/game-runtime: Kotlin, zstd-jni, mediaproc and Forgeguard jars plus manifest.json."
    val output = layout.buildDirectory.dir("game-runtime")
    // A plain file collection, because the configuration cache cannot store a Configuration.
    val agent = files(forgeguardAgent)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("calebxzau.rdi.service.GameRuntimeExportKt")
    inputs.files(agent)
    outputs.dir(output)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(output.get().asFile.absolutePath, agent.singleFile.absolutePath)
    })
}
