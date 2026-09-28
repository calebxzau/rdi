import groovy.lang.Closure
import com.gtnewhorizons.retrofuturagradle.minecraft.RunMinecraftTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.tasks.Jar
import org.gradle.internal.classpath.Instrumented.systemProperty
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("com.gtnewhorizons.gtnhconvention")
}
evaluationDependsOn(":s-mc-common")
val commonProject = project(":s-mc-common")
val sourceSets = extensions.getByName("sourceSets") as SourceSetContainer

base {
    archivesName = "rdi"
}

version = "5-mc-server-1.7.10-forge"
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

kotlin.sourceSets.named("main") {
    kotlin.srcDir(file("../../../mc/rcmd/src/main/kotlin"))
    kotlin.srcDir(file("../../../mc/rmcp/common/src/main/kotlin"))
    kotlin.srcDir(file("../../../ktutils/std/src/main/kotlin"))
}

val shaded = configurations.create("shaded")
configurations.named("implementation") {
    extendsFrom(shaded)
}

dependencies {
    add("shaded", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(shaded.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.named<RunMinecraftTask>("runServer21") {
    systemProperty("rdi.ihq.url", "127.0.0.1:65231")
    systemProperty("rdi.host.id", "697b286a8e2f0e5c09a78b22")
    systemProperty("mixin.hotSwap", "true")

    extraArgs.addAll(
        "--mixin",
        "mixins.rdi.json",
        "--nogui"
    )
}

@Suppress("UNCHECKED_CAST")
val registerServerCopyTask = commonProject.extensions.extraProperties["registerServerCopyTask"] as Closure<*>
registerServerCopyTask.call(project)
