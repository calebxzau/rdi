package calebxzau.rdi.mclaunch

import calebxzau.rdi.common.logging.Loggers
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.Continuation
import kotlinx.coroutines.Job

//用RDI的mc运行库，不用各个Mod提供的
object GameKotlinRuntime {
    private val lgr by Loggers
    private const val CACHE_SCHEMA = "v3"
    private const val MAX_NESTED_DEPTH = 4
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
        // RdiZstdRuntime已加入启动classpath，Mod自带的会导致JPMS模块重名
        "com.github.luben:zstd-jni",
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
        val plan: JarPlan,
        val isKff: Boolean,
    )

    private data class JarJarDependency(
        val path: String,
        val group: String,
        val artifact: String,
    )

    // 一层jar（顶层Mod或jarjar嵌套jar）需要做的精简
    private class JarPlan(
        val metadata: JsonObject?,
        val removedEntries: Set<String>,
        val stripShadedRuntime: Boolean,
        val nested: Map<String, NestedJar>,
    ) {
        val needsNormalization: Boolean
            get() = removedEntries.isNotEmpty() || stripShadedRuntime || nested.isNotEmpty()
    }

    private class NestedJar(val content: InMemoryJarContent, val plan: JarPlan)

    private interface JarContent {
        val entryNames: List<String>
        fun contains(name: String): Boolean
        fun read(name: String): ByteArray?
        fun copyTo(name: String, output: ZipOutputStream)
    }

    private class ZipFileContent(private val zip: ZipFile) : JarContent {
        override val entryNames: List<String> =
            zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()

        override fun contains(name: String): Boolean = zip.getEntry(name) != null

        override fun read(name: String): ByteArray? =
            zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } }

        override fun copyTo(name: String, output: ZipOutputStream) {
            val entry = zip.getEntry(name) ?: return
            zip.getInputStream(entry).use { it.copyTo(output) }
        }
    }

    private class InMemoryJarContent(bytes: ByteArray) : JarContent {
        private val entries = LinkedHashMap<String, ByteArray>().apply {
            ZipInputStream(bytes.inputStream()).use { input ->
                generateSequence { input.nextEntry }
                    .filterNot { it.isDirectory }
                    .forEach { entry -> putIfAbsent(entry.name, input.readBytes()) }
            }
        }

        override val entryNames: List<String> get() = entries.keys.toList()

        override fun contains(name: String): Boolean = name in entries

        override fun read(name: String): ByteArray? = entries[name]

        override fun copyTo(name: String, output: ZipOutputStream) {
            entries[name]?.let(output::write)
        }
    }

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
            val plan = planJar(ZipFileContent(zip), topLevel = true, depth = 0) ?: return@use null
            if (!plan.needsNormalization) return@use null
            val kffDependencies = plan.metadata?.dependencies().orEmpty()
                .filter { it.group == KFF_GROUP && it.artifact in kffArtifacts }
            KotlinArchive(
                file = file,
                plan = plan,
                isKff = kffDependencies.size == kffArtifacts.size &&
                    kffDependencies.map { it.artifact }.toSet() == kffArtifacts,
            )
        }
    }.getOrNull()

    // 嵌套jar只看jarjar声明的运行库，不按kotlin/包名剥离，避免误删kotlinx-datetime这类非RDI提供的库
    private fun planJar(jar: JarContent, topLevel: Boolean, depth: Int): JarPlan? {
        val metadata = jar.read(JARJAR_METADATA)?.let { bytes ->
            serdesJson.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)).jsonObject
        }
        if (topLevel && metadata == null) return null
        val dependencies = metadata?.dependencies().orEmpty()
        val providedRuntimeDependencies = dependencies.filter(::isProvidedRuntime)
        if (providedRuntimeDependencies.any { !jar.contains(it.path) }) return null
        val nested = if (depth >= MAX_NESTED_DEPTH) emptyMap() else dependencies
            .filterNot(::isProvidedRuntime)
            .mapNotNull { dependency ->
                val bytes = jar.read(dependency.path) ?: return@mapNotNull null
                val content = runCatching { InMemoryJarContent(bytes) }.getOrElse { error ->
                    lgr.warn(error) { "无法读取嵌套jar，跳过Kotlin运行库检查: ${dependency.path}" }
                    return@mapNotNull null
                }
                val plan = planJar(content, topLevel = false, depth = depth + 1)
                    ?.takeIf { it.needsNormalization }
                    ?: return@mapNotNull null
                dependency.path to NestedJar(content, plan)
            }
            .toMap()
        return JarPlan(
            metadata = metadata,
            removedEntries = providedRuntimeDependencies.mapTo(linkedSetOf()) { it.path },
            stripShadedRuntime = topLevel && jar.entryNames.any(::isShadedKotlinEntry),
            nested = nested,
        )
    }

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
                writeThinJar(ZipFileContent(source), archive.plan, output)
            }
        }
    }

    private fun writeThinJar(jar: JarContent, plan: JarPlan, output: ZipOutputStream) {
        val written = HashSet<String>()
        jar.entryNames
            .filter { shouldPreserve(it, plan) }
            .forEach { name ->
                if (!written.add(name)) return@forEach
                output.putNextEntry(ZipEntry(name))
                val nested = plan.nested[name]
                if (nested != null) {
                    output.write(thinNestedJar(nested))
                } else {
                    jar.copyTo(name, output)
                }
                output.closeEntry()
            }
        val metadata = plan.metadata ?: return
        val filteredMetadata = buildJsonObject {
            metadata.forEach { (key, value) ->
                put(key, if (key == "jars") {
                    JsonArray(
                        metadata.dependencies()
                            .filter { it.path !in plan.removedEntries }
                            .mapNotNull { dependency ->
                                metadata["jars"]?.jsonArray?.firstOrNull { element ->
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

    private fun thinNestedJar(nested: NestedJar): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output -> writeThinJar(nested.content, nested.plan, output) }
        return bytes.toByteArray()
    }

    private fun shouldPreserve(name: String, plan: JarPlan): Boolean = when {
        name == "META-INF/MANIFEST.MF" -> true
        name == JARJAR_METADATA -> false
        name in plan.removedEntries -> false
        isSignatureEntry(name) -> false
        plan.stripShadedRuntime && isShadedKotlinEntry(name) -> false
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

    private fun isThinKotlinMod(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            val content = ZipFileContent(zip)
            val plan = planJar(content, topLevel = true, depth = 0) ?: return@use false
            !plan.needsNormalization &&
                plan.metadata?.dependencies().orEmpty().all { content.contains(it.path) }
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
