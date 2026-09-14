pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(
    ":assets",
    ":assets:fonts",
    ":theme",
    ":code-editor",
    ":code-editor-ui",
    ":database",
    ":client-store",
    ":misc",
    ":model",
    ":net",
    ":archive",
    ":anvilrw",
    ":quests",
    ":blessing-skin",
    ":mediaproc",
    ":pack-proc",
    ":mod-catalog",
    ":mod-catalog-tools",
  //  ":webview2",
    ":forgeguard",
    ":mclaunch",
    ":mc-install",
    ":modpack-test",
    ":player-info",
    ":player-model",
    ":player-model-core",
    ":render-core",
    ":bg-renderer",
    ":early-display",
    ":local-mc-proxy",
    ":mc-proxy"
)
project(":assets").projectDir = file("assets")
project(":theme").projectDir = file("theme")
project(":code-editor").projectDir = file("code-editor")
project(":code-editor-ui").projectDir = file("code-editor-ui")
project(":database").projectDir = file("database")
project(":client-store").projectDir = file("client-store")
project(":misc").projectDir = file("../../common/misc")
project(":model").projectDir = file("../../common/model")
project(":net").projectDir = file("../../common/net")
project(":archive").projectDir = file("../../common/archive")
project(":anvilrw").projectDir = file("../../common/anvilrw")
project(":quests").projectDir = file("../../common/quests")
project(":blessing-skin").projectDir = file("blessing-skin")
project(":mediaproc").projectDir = file("../mediaproc")
project(":pack-proc").projectDir = file("../pack-proc")
project(":mod-catalog").projectDir = file("mod-catalog")
project(":mod-catalog-tools").projectDir = file("mod-catalog-tools")
//project(":webview2").projectDir = file("webview2")
project(":forgeguard").projectDir = file("../../mc/forgeguard")
project(":mclaunch").projectDir = file("../mclaunch")
project(":mc-install").projectDir = file("../mc-install")
project(":modpack-test").projectDir = file("../modpack-test")
project(":player-info").projectDir = file("../player-info")
project(":player-model").projectDir = file("../player-model")
project(":player-model-core").projectDir = file("../player-model-core")
project(":render-core").projectDir = file("../render-core")
project(":bg-renderer").projectDir = file("../bg-renderer")
project(":early-display").projectDir = file("../early-display")
project(":local-mc-proxy").projectDir = file("../local-mc-proxy")
project(":mc-proxy").projectDir = file("../../mc/proxy")
