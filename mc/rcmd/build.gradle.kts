import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")  version "2.4.21"
    `java-library`
}

group = "calebxzhou.rdi.mc.rcmd"
version = "0.1"



repositories {
    mavenLocal()
    mavenCentral()
}


dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.4.21")
    testImplementation(kotlin("test"))
}
