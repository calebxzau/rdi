import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.sqldelight)
}

repositories {
    mavenCentral()
    mavenLocal()
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(libs.versions.java.get()))
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

dependencies {
    implementation(project(":model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.sqlite.driver) {
        exclude(group = "org.xerial", module = "sqlite-jdbc")
    }
    implementation("org.xerial:sqlite-jdbc:${libs.versions.sqlite.get()}:without-natives")
    runtimeOnly(project(":sqlite-natives"))

    testImplementation(kotlin("test"))
}

sqldelight {
    databases {
        register("RClientDatabase") {
            packageName.set("calebxzhou.rdi.client.database")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
base {
    archivesName.set("rdi-database")
}
