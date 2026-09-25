plugins {
    kotlin("jvm") version "2.4.10"
}

group = "calebxzhou.rdi.mc.firmsection"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

kotlin {
    // jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
