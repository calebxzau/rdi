package calebxzau.rdi.mclaunch

import calebxzhou.rdi.common.util.sha1
import calebxzhou.rdi.common.model.McVersion
import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.Continuation
import kotlinx.coroutines.Job

//用RDI的mc运行库，不用各个Mod提供的
object GameKotlinRuntime {
    private const val CACHE_SCHEMA = "v2"
    private const val JARJAR_METADATA = "META-INF/jarjar/metadata.json"
    private const val KFF_GROUP = "thedarkcolour"
    private val kffArtifacts = setOf("kfflang", "kfflib", "kffmod")
    private val providedRuntimeArtifacts = setOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains.kotlin:kotlin-reflect",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
        "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
        "org.jetbrains.kotlinx:kotlinx-coroutines-jdk8",
        "org.jetbrains.kotlinx:kotlinx-serialization-core-jvm",
        "org.jetbrains.kotlinx:kotlinx-serialization-json-jvm",
        // KFF5.11 uses an empty group and the module name as the artifact.
        ":kotlinx.serialization.core",
        ":kotlinx.serialization.json",
       /* "io.heapy.kotaml:kotaml",
        "io.heapy.kotaml:kotaml-jvm",
        "com.squareup.okio:okio-jvm",
        "it.krzeminski:snakeyaml-engine-kmp-jvm",
        "net.thauvin.erik.urlencoder:urlencoder-lib-jvm",
        "io.fusionauth:java-http",*/
    )

    fun prepare(
        mcVersion: McVersion,
        modsDir: File,
        cacheRoot: File,
    ): Result<List<File>> = runCatching {
        check(mcVersion == McVersion.V201 || mcVersion == McVersion.V211) {
            "RDI游戏运行库只支持MC20和MC21"
        }
        val runtime = resolveRuntimeClasspath()
        val archives = detectKotlinArchives(modsDir)
        val kffArchives = archives.filter { it.isKff }
        check(kffArchives.size <= 1) {
            "发现多个Kotlin for Forge文件: ${kffArchives.joinToString { it.file.name }}"
        }
        archives.forEach { normalizeKotlinMod(it, cacheRoot) }
        runtime
    }

    private fun resolveRuntimeClasspath(): List<File> {
        val libraries = listOf(
            "kotlin-stdlib" to classSource(Continuation::class.java),
            "kotlin-reflect" to classSource("kotlin.reflect.jvm.ReflectJvmMapping"),
            "kotlinx-coroutines-core" to classSource(Job::class.java),
            "kotlinx-serialization-core" to classSource(KSerializer::class.java),
            "kotlinx-serialization-json" to classSource(Json::class.java),
        /*
          in future
          "kotaml" to classSource("com.charleskorn.kaml.Yaml"),
            "okio" to classSource("okio.Path"),
            "snakeyaml-engine" to classSource("it.krzeminski.snakeyaml.engine.kmp.api.Load"),
            "urlencoder" to classSource("net.thauvin.erik.urlencoder.UrlEncoderUtil"),
            "java-http" to classSource("io.fusionauth.http.HTTPMethod"),
      */  )
        libraries.forEach { (name, file) ->
            check(file.isFile) { "RDI${name}运行库不存在: ${file.absolutePath}" }
            check(file.extension.equals("jar", ignoreCase = true)) {
                "RDI${name}运行库不是JAR: ${file.absolutePath}"
            }
        }
        check(libraries.map { it.second.absolutePath }.distinct().size == libraries.size) {
            "RDI游戏运行库必须由${libraries.size}个独立JAR提供: " +
                libraries.joinToString { (name, file) -> "$name=${file.name}" }
        }
        return libraries.map { it.second }
    }

    private fun classSource(type: Class<*>): File =
        File(type.protectionDomain.codeSource.location.toURI()).absoluteFile

    private fun classSource(className: String): File = classSource(Class.forName(className))

    private data class KotlinArchive(
        val file: File,
        val metadata: JsonObject,
        val removedEntries: Set<String>,
        val isKff: Boolean,
        val needsNormalization: Boolean,
    )

    private data class JarJarDependency(
        val path: String,
        val group: String,
        val artifact: String,
    )

    private fun detectKotlinArchives(modsDir: File): List<KotlinArchive> {
        if (!modsDir.isDirectory) return emptyList()
        return modsDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .mapNotNull(::inspectKotlinArchive)
            .toList()
    }

    private fun inspectKotlinArchive(file: File): KotlinArchive? = runCatching {
        ZipFile(file).use { zip ->
            val metadataEntry = zip.getEntry(JARJAR_METADATA) ?: return@use null
            val metadata = serdesJson.parseToJsonElement(
                zip.getInputStream(metadataEntry).bufferedReader(StandardCharsets.UTF_8).readText()
            ).jsonObject
            val dependencies = metadata.dependencies()
            val providedRuntimeDependencies = dependencies.filter(::isProvidedRuntime)
            val hasShadedRuntime = zip.entries().asSequence().any { entry ->
                !entry.isDirectory && isShadedKotlinEntry(entry.name)
            }
            if (providedRuntimeDependencies.isEmpty() && !hasShadedRuntime) return@use null
            if (providedRuntimeDependencies.any { zip.getEntry(it.path) == null }) return@use null
            val kffDependencies = dependencies.filter { it.group == KFF_GROUP && it.artifact in kffArtifacts }
            KotlinArchive(
                file = file,
                metadata = metadata,
                removedEntries = providedRuntimeDependencies.mapTo(linkedSetOf()) { it.path },
                isKff = kffDependencies.size == kffArtifacts.size &&
                    kffDependencies.map { it.artifact }.toSet() == kffArtifacts,
                needsNormalization = providedRuntimeDependencies.isNotEmpty() || hasShadedRuntime,
            )
        }
    }.getOrNull()

    private fun JsonObject.dependencies(): List<JarJarDependency> =
        (this["jars"] as? JsonArray).orEmpty().mapNotNull { element ->
            val jar = element as? JsonObject ?: return@mapNotNull null
            val identifier = jar["identifier"] as? JsonObject ?: return@mapNotNull null
            val path = jar["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val group = identifier["group"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val artifact = identifier["artifact"]?.jsonPrimitive?.contentOrNull.orEmpty()
            JarJarDependency(path, group, artifact)
        }

    private fun isProvidedRuntime(dependency: JarJarDependency): Boolean =
        "${dependency.group}:${dependency.artifact}" in providedRuntimeArtifacts

    private fun normalizeKotlinMod(archive: KotlinArchive, cacheRoot: File) {
        if (!archive.needsNormalization) return
        val sourceSha1 = archive.file.sha1.lowercase()
        val root = cacheRoot.resolve("thin-kotlin-mod").apply { mkdirs() }
        val originalRoot = cacheRoot.resolve("original-kotlin-mod").apply { mkdirs() }
        val normalized = root.resolve("$CACHE_SCHEMA-$sourceSha1.jar")
        val original = originalRoot.resolve("$sourceSha1.jar")
        if (!original.isFile || !original.sha1.equals(sourceSha1, ignoreCase = true)) {
            Files.copy(archive.file.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!normalized.isFile || !isThinKotlinMod(normalized)) {
            val temporary = Files.createTempFile(root.toPath(), "${normalized.name}-", ".tmp").toFile()
            try {
                writeThinKotlinMod(archive, temporary)
                check(isThinKotlinMod(temporary)) { "生成的thin Kotlin Mod校验失败: ${temporary.absolutePath}" }
                moveAtomically(temporary, normalized)
            } finally {
                Files.deleteIfExists(temporary.toPath())
            }
        }
        val parent = archive.file.parentFile ?: error("Kotlin Mod文件缺少父目录: ${archive.file.absolutePath}")
        val replacement = Files.createTempFile(parent.toPath(), "${archive.file.name}.rdi-kotlin-", ".tmp").toFile()
        try {
            Files.copy(normalized.toPath(), replacement.toPath(), StandardCopyOption.REPLACE_EXISTING)
            moveAtomically(replacement, archive.file)
        } finally {
            Files.deleteIfExists(replacement.toPath())
        }
    }

    private fun writeThinKotlinMod(archive: KotlinArchive, target: File) {
        target.parentFile?.mkdirs()
        ZipFile(archive.file).use { source ->
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                val written = HashSet<String>()
                source.entries().asSequence()
                    .filter { shouldPreserve(it.name, archive.removedEntries) }
                    .forEach { writeEntry(source, it, output, written) }
                val filteredMetadata = buildJsonObject {
                    archive.metadata.forEach { (key, value) ->
                        put(key, if (key == "jars") {
                            JsonArray(
                                archive.metadata.dependencies()
                                    .filter { it.path !in archive.removedEntries }
                                    .mapNotNull { dependency ->
                                        archive.metadata["jars"]?.jsonArray?.firstOrNull { element ->
                                            element.jsonObject["path"]?.jsonPrimitive?.contentOrNull == dependency.path
                                        }
                                    }
                            )
                        } else {
                            value
                        })
                    }
                }
                output.putNextEntry(ZipEntry(JARJAR_METADATA))
                output.write(filteredMetadata.toString().toByteArray(StandardCharsets.UTF_8))
                output.closeEntry()
            }
        }
    }

    private fun shouldPreserve(name: String, removedEntries: Set<String>): Boolean = when {
        name == "META-INF/MANIFEST.MF" -> true
        name == JARJAR_METADATA -> false
        name in removedEntries -> false
        isSignatureEntry(name) -> false
        isShadedKotlinEntry(name) -> false
        else -> true
    }

    private fun isShadedKotlinEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        return normalized.startsWith("kotlin/") ||
            normalized.startsWith("kotlinx/") ||
            normalized.startsWith("_COROUTINE/")
    }

    private fun isSignatureEntry(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (!normalized.startsWith("META-INF/")) return false
        return normalized.substringAfterLast('/')
            .substringAfterLast('.', "")
            .uppercase() in setOf("SF", "RSA", "DSA")
    }

    private fun writeEntry(
        source: ZipFile,
        entry: ZipEntry,
        output: ZipOutputStream,
        written: MutableSet<String>,
    ) {
        if (entry.isDirectory || !written.add(entry.name)) return
        output.putNextEntry(ZipEntry(entry.name))
        source.getInputStream(entry).use { it.copyTo(output) }
        output.closeEntry()
    }

    private fun isThinKotlinMod(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            val metadataEntry = zip.getEntry(JARJAR_METADATA) ?: return@use false
            val metadata = serdesJson.parseToJsonElement(
                zip.getInputStream(metadataEntry).bufferedReader(StandardCharsets.UTF_8).readText()
            ).jsonObject
            val dependencies = metadata.dependencies()
            dependencies.none(::isProvidedRuntime) &&
                dependencies.all { zip.getEntry(it.path) != null } &&
                zip.entries().asSequence().none { !it.isDirectory && isShadedKotlinEntry(it.name) }
        }
    }.getOrDefault(false)

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
