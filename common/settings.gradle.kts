pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    }
}

rootProject.name = "common"

include(":misc")
include(":model")
include(":net")
include(":archive")
include(":anvilrw")
include(":quests")
include(":ai")
