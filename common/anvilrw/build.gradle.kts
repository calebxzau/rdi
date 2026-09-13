plugins {
    `java-library`
    kotlin("jvm")
}

group = "calebxzhou.rdi.common"
//version = "0.1"

repositories {
    mavenLocal()
    mavenCentral()
}



dependencies {
    api(libs.knbt)
    compileOnly(libs.zstd.jni)
    testImplementation(kotlin("test"))
    testCompileOnly(libs.zstd.jni)
    testRuntimeOnly(libs.zstd.jni)
}
base {
    archivesName.set("rdi-anvilrw")
}
