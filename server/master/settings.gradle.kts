pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "master"
include(":misc", ":model", ":net", ":archive", ":anvilrw", ":quests")
project(":misc").projectDir = file("../../common/misc")
project(":model").projectDir = file("../../common/model")
project(":net").projectDir = file("../../common/net")
project(":archive").projectDir = file("../../common/archive")
project(":anvilrw").projectDir = file("../../common/anvilrw")
project(":quests").projectDir = file("../../common/quests")
