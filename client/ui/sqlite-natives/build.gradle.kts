plugins {
    `java-library`
}

apply(from = "../../gradle/target-platform.gradle.kts")
val rdiTargetPlatform: String by extra
val sqliteClassifier: String by extra
val sqliteNativePath: String by extra

repositories {
    mavenCentral()
    mavenLocal()
}

// Ships only the sqlite-jdbc native library of the target platform, so rdi-database.jar and
// rdi-mod-catalog.jar stay byte-identical across platforms.
val sqliteNatives by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    sqliteNatives("org.xerial:sqlite-jdbc:${libs.versions.sqlite.get()}:$sqliteClassifier")
}

tasks.named<Jar>("jar") {
    archiveFileName.set("rdi-sqlite-natives-$rdiTargetPlatform.jar")
    from({ sqliteNatives.map(::zipTree) }) {
        include("org/sqlite/native/$sqliteNativePath")
    }
}
