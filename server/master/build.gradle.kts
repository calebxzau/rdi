import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import sun.jvmstat.monitor.MonitoredVmUtil.mainClass

plugins {
    application
    idea
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

group = "calebxzhou.rdi"
version = "1.0-SNAPSHOT"
idea {
    module {
        excludeDirs = excludeDirs + file("run") + file("logs")
    }
}
repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "Github Packages"
        url = uri("https://maven.pkg.github.com/")
    }
}

dependencies {
    implementation(project(":misc"))
    implementation(project(":model"))
    implementation(project(":net"))
    implementation(project(":archive"))
    implementation(project(":anvilrw"))
    implementation(libs.logback.classic)
    implementation(libs.bundles.ktor.server)
    implementation("io.netty:netty-tcnative-boringssl-static:${libs.versions.netty.tcnative.get()}:linux-x86_64")
    implementation(libs.bundles.ktor.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tomlkt)
    implementation(libs.kotlin.logging.jvm)
    // Source: https://mvnrepository.com/artifact/org.mongodb/bson-kotlinx
    implementation(libs.mongodb.bson.kotlinx)
    implementation(libs.mongodb.driver.kotlin.coroutine)
    implementation(libs.knbt)
    implementation(libs.bundles.docker.java)
    implementation(libs.commons.compress)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.jakarta.mail)
    implementation(libs.ip2region)
    implementation(libs.zstd.jni)
    implementation(libs.koin.core)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("io.ktor:ktor-server-test-host:${libs.versions.ktor.get()}")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

val shadowJar = tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("ihq.jar")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching("META-INF/services/**") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}

tasks.register("buildFatJar") {
    group = "build"
    description = "Builds a combined JAR of project and runtime dependencies."
    dependsOn(shadowJar)
}

application {
    mainClass.set("calebxzhou.rdi.master.RDIKt")
}

val runDir = layout.projectDirectory.dir("run").asFile

kotlin {
    jvmToolchain(25)
}

tasks.named<Test>("test") {
    enabled = false
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    enabled = true
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    workingDir = runDir
    doFirst {
        workingDir.mkdirs()
    }
}

tasks.withType<JavaExec>().configureEach {
    workingDir = runDir
    doFirst {
        workingDir.mkdirs()
    }
}

tasks.register<Test>("modpackServiceTest") {
    group = "verification"
    description = "Runs focused Legacy ModpackService behavior tests."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/calebxzhou/rdi/master/service/ModpackService*Test.class")
    include("**/calebxzhou/rdi/master/service/ModpackRoutesTest.class")
    include("**/calebxzhou/rdi/master/service/ModpackParallelUploadServiceTest.class")
    include("**/calebxzhou/rdi/master/service/ModpackUploadTempStorageTest.class")
    systemProperty("net.bytebuddy.experimental", "true")
    useJUnitPlatform()
}

tasks.register<Test>("baseWorldTest") {
    group = "verification"
    description = "Runs focused BaseWorld persistence and route tests."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/calebxzau/rdi/server/service/baseworld/*Test.class")
    include("**/calebxzhou/rdi/master/service/host/HostInstallServiceTest.class")
    systemProperty("net.bytebuddy.experimental", "true")
    useJUnitPlatform()
}

tasks.register<Test>("gameStatusTest") {
    group = "verification"
    description = "Runs focused Minecraft server status listener tests."
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/calebxzau/rdi/master/service/GameStatusServiceTest.class")
    useJUnitPlatform()
}
//
//tasks.register<Test>("accountMirrorTest") {
//    group = "verification"
//    description = "Runs the focused PostgreSQL account mirror tests."
//    dependsOn(tasks.named("testClasses"))
//    testClassesDirs = sourceSets["test"].output.classesDirs
//    classpath = sourceSets["test"].runtimeClasspath
//    include("**/calebxzau/rdi/master/account/**/*Test.class")
//    systemProperty("net.bytebuddy.experimental", "true")
//    useJUnitPlatform()
//}
//
// tasks.register<Test>("modpack2RepositoryIntegrationTest") {
//    group = "verification"
//    description = "Runs the PostgreSQL18 Modpack2 repository integration tests."
//    dependsOn(tasks.named("testClasses"))
//    testClassesDirs = sourceSets["test"].output.classesDirs
//    classpath = sourceSets["test"].runtimeClasspath
//    include("**/calebxzau/rdi/server/modpack2/Modpack2RepoIntegrationTest.class")
//    useJUnitPlatform()
//}
//
//tasks.register<Test>("friendSystemTest") {
//    group = "verification"
//    description = "Runs focused friend/mail persistence contract tests."
//    dependsOn(tasks.named("testClasses"))
//    testClassesDirs = sourceSets["test"].output.classesDirs
//    classpath = sourceSets["test"].runtimeClasspath
//    include("**/calebxzau/rdi/server/friend/FriendPersistenceContractTest.class")
//    include("**/calebxzau/rdi/server/friend/FriendMailRepositoryIntegrationTest.class")
//    useJUnitPlatform()
//}
//
//tasks.register<Test>("modpack2ApiTest") {
//    group = "verification"
//    description = "Runs focused Modpack2 archive, storage, upload, and host download tests."
//    dependsOn(tasks.named("testClasses"))
//    testClassesDirs = sourceSets["test"].output.classesDirs
//    classpath = sourceSets["test"].runtimeClasspath
//    include("**/calebxzau/rdi/server/modpack2/Modpack2ArchiveIOTest.class")
//    include("**/calebxzau/rdi/server/modpack2/Modpack2ContentValidatorTest.class")
//    include("**/calebxzau/rdi/server/modpack2/Modpack2ManifestValidatorTest.class")
//    include("**/calebxzau/rdi/server/modpack2/Modpack2VersionStorageTest.class")
//    include("**/calebxzau/rdi/server/modpack/Modpack2RouteTest.class")
//    include("**/calebxzhou/rdi/master/service/ModpackParallelUploadServiceTest.class")
//    include("**/calebxzhou/rdi/master/service/host2/Host2ModDownloadServiceTest.class")
//    include("**/calebxzhou/rdi/master/service/host2/Host2ContentRulesTest.class")
//    useJUnitPlatform()
//}

tasks.register("出core") {
    // Uses Project APIs in the action; mark as not CC-compatible to avoid serialization errors.
    notCompatibleWithConfigurationCache("uses project file operations at execution time")
    dependsOn(tasks.named("buildFatJar"))

    doLast {
        val jarFile = layout.buildDirectory.file("libs/ihq.jar").get().asFile
        if (!jarFile.exists()) {
            throw GradleException("未找到构建产物: $jarFile")
        }
        val targetDir = file("\\\\rdi\\rdi55\\ihq")
        targetDir.mkdirs()
        val destFile = targetDir.resolve(jarFile.name)
        Files.copy(
            jarFile.toPath(),
            destFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )
        println("已复制 $jarFile 到 $destFile")
    }
}
tasks.test {
    testLogging.showStandardStreams = true
}
