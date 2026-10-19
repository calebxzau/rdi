package calebxzhou.rdi.client.model

import calebxzau.rdi.client.platform.loadResourceStream
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.client.service.mcInstall

val McVersion.metadata
    get() = loadResourceStream("mcmeta/$mcVer.json").bufferedReader().readText()
        .let { serdesJson.decodeFromString<MojangVersionManifest>(it) }
val McVersion.baseDir get() = mcInstall.versionListDir.resolve(mcVer)
val McVersion.nativesDir get() = baseDir.resolve("natives")
val McVersion.firstLoader get() = loaderVersions.keys.first()
val McVersion.firstLoaderVersion get() = loaderVersions.values.first()
val McVersion.firstLoaderDir get() = loaderVersions.values.first().let { mcInstall.versionListDir.resolve(it.dirName) }
val McVersion.manifest: MojangVersionManifest get() = metadata
val McVersion.loaderManifest: MojangVersionManifest
    get() = mcInstall.versionListDir.resolve(firstLoaderVersion.dirName).resolve(firstLoaderVersion.dirName + ".json")
        .let { serdesJson.decodeFromString(it.readText()) }
