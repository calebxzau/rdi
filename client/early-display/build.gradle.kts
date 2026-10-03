plugins {
    `java-library`
}

group = "calebxzhou.rdi"
version = "1"

repositories {
    mavenCentral()
    maven("https://maven.minecraftforge.net")
    maven("https://libraries.minecraft.net")
    maven("https://maven.neoforged.net/releases")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    compileOnly("net.minecraftforge:fmlloader:1.20.1-47.4.20")
    compileOnly("net.neoforged.fancymodloader:loader:4.0.42")
    compileOnly("net.neoforged.fancymodloader:earlydisplay:4.0.42")
    compileOnly("net.sf.jopt-simple:jopt-simple:5.0.4")
    compileOnly("org.jetbrains:annotations:24.1.0")
    compileOnly("org.lwjgl:lwjgl:3.3.3")
    compileOnly("org.lwjgl:lwjgl-glfw:3.3.3")
    compileOnly("org.lwjgl:lwjgl-opengl:3.3.3")
    compileOnly("org.lwjgl:lwjgl-stb:3.3.3")
    compileOnly("org.lwjgl:lwjgl-tinyfd:3.3.3")
    compileOnly("org.slf4j:slf4j-api:2.0.9")
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.apache.logging.log4j:log4j-core:2.22.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveFileName.set("rdi-early-display.jar")
    manifest.attributes["Automatic-Module-Name"] = "rdi.earlydisplay"
}
