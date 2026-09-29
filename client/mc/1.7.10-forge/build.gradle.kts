import groovy.lang.Closure
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.internal.classpath.Instrumented.systemProperty
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import kotlin.io.encoding.Base64

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gtnewhorizons.gtnhconvention")
}
evaluationDependsOn(":c-mc-common")
val commonProject = project(":c-mc-common")
val sourceSets = extensions.getByName("sourceSets") as SourceSetContainer

base {
    archivesName = "rdi"
}

version = "5-mc-client-1.7.10-forge"
group = "calebxzhou.rdi"

sourceSets.named("main") {
    java.srcDir(commonProject.file("src/main/java"))
    java.srcDir(file("../common2/src/main/java"))
    resources.srcDir(commonProject.file("src/main/resources"))
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
    sourceSets.all {
        languageSettings {
            optIn("kotlinx.serialization.ExperimentalSerializationApi")
        }
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
kotlin.sourceSets.named("main") {
    kotlin.srcDir(file("../../../mc/rcmd/src/main/kotlin"))
    kotlin.srcDir(file("../../../mc/rmcp/common/src/main/kotlin"))
    kotlin.srcDir(file("../../../mc/rmcp/client/src/main/kotlin"))
    kotlin.srcDir(file("../../../ktutils/std/src/main/kotlin"))
}

val shaded = configurations.create("shaded")
configurations.named("implementation") {
    extendsFrom(shaded)
}

dependencies {
    add("shaded", "io.fusionauth:java-http:1.4.0")
    implementation( "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    add("shaded", "io.heapy.kotaml:kotaml:0.108.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-serialization-json")
    }
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(shaded.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

val defaultPlayArgRaw = """
http://127.0.0.1:65231
127.0.0.1:8098
测试测试12123大世界
8098
68b314bb-adaf-52dd-ab96-b5ed00000000
dev1
""".trimIndent()

val anotherPlayArgRaw = """
http://127.0.0.1:65231
127.0.0.1:8098
另一个测试世界
8098
68b314bb-adaf-52dd-ab96-b5ee00000000
dev2
""".trimIndent()

fun encodePlayArg(raw: String) = Base64.encode(raw.encodeToByteArray())

val requestedTasks = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }.toSet()
val selectedPlayArg = when {
    "runClient21Another" in requestedTasks -> anotherPlayArgRaw
    else -> defaultPlayArgRaw
}

tasks.named<RunMinecraftTask>("runClient21") {
    systemProperty("rdi.play", encodePlayArg(selectedPlayArg))
    systemProperty("rdi.debug",true)
    systemProperty("mixin.hotSwap", "true")

    extraArgs.addAll(
        "--mixin",
        "mixins.rdi.json",
        "--width",
        "2560",
        "--height",
        "1440",
        "--server",
        "127.0.0.1",
        "--port",
        "8098"
    )
}

tasks.register("runClient21Another") {
    group = "minecraft"
    description = "Run Minecraft client with another RDI play arg"
    dependsOn("runClient21")
}

@Suppress("UNCHECKED_CAST")
val registerClientCopyTask = commonProject.extensions.extraProperties["registerClientCopyTask"] as Closure<*>
registerClientCopyTask.call(project)

// Run tests and launch tasks from this module's run directory.
val rdiRunDirectory = layout.projectDirectory.dir("run").asFile
tasks.withType<Test>().configureEach {
    workingDir = rdiRunDirectory
    doFirst { rdiRunDirectory.mkdirs() }
}
tasks.withType<JavaExec>().configureEach {
    // Keep explicit run directories such as run2, run/client, or run/server.
    if (workingDir != project.projectDir) return@configureEach
    workingDir = rdiRunDirectory
    doFirst { rdiRunDirectory.mkdirs() }
}
