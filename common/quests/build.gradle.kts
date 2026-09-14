import java.io.File

plugins {
    `java-library`
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "calebxzau.rdi.common"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(libs.knbt)
    api(libs.kotlinx.serialization.json)

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
val runDir = layout.projectDirectory.dir("run").asFile

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Local pack fixtures are opt-in and live outside the repository.
    workingDir = runDir
    doFirst {
        workingDir.mkdirs()
    }
    val samples = providers.environmentVariable("RDI_FTB_QUESTS_SAMPLES").orElse("")
    inputs.property("questSamples", samples)
    inputs.files(samples.map { value -> value.split(File.pathSeparator).filter { it.isNotBlank() } })
        .withPropertyName("questSampleFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging.showStandardStreams = true
}

base {
    archivesName.set("rdi-quests")
}
