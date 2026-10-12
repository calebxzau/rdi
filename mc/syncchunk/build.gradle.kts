plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

dependencies {
    // Minecraft ships Gson and every loader build already depends on zstd-jni.
    implementation("com.google.code.gson:gson:2.10")
    implementation("com.github.luben:zstd-jni:1.5.7-11")
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks.test {
    useJUnitPlatform()
}
