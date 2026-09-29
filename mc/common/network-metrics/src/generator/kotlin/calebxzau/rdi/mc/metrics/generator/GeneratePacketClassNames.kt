package calebxzau.rdi.mc.metrics.generator

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/** Generates Java references for vanilla packet classes without loading Minecraft classes. */
object GeneratePacketClassNames {
    private const val PACKET_INTERNAL_NAME = "net/minecraft/network/protocol/Packet"
    private const val REQUIRED_PACKET_INTERNAL_NAME =
        "net/minecraft/network/protocol/game/ClientboundKeepAlivePacket"
    private const val GENERATED_PACKAGE = "calebxzau.rdi.mc.v20.server.network"
    private const val GENERATED_CLASS = "PacketClassNames20"
    private const val GENERATED_RELATIVE_PATH =
        "calebxzau/rdi/mc/v20/server/network/PacketClassNames20.java"

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) {
            "Usage: GeneratePacketClassNames <output-directory> <minecraft-jar-or-classes-directory> [additional-inputs...]"
        }

        val outputDirectory = Path.of(args[0])
        val inputs = args.drop(1).map { Path.of(it) }
        val classes = scanMinecraftInputs(inputs)
        val packetNames = findConcretePackets(classes)
        require(packetNames.isNotEmpty()) {
            "No concrete Minecraft Packet implementations were found in: ${inputs.joinToString()}"
        }

        val source = generateSource(packetNames)
        val output = outputDirectory.resolve(GENERATED_RELATIVE_PATH)
        Files.createDirectories(output.parent)
        Files.writeString(output, source)
        println("Generated ${packetNames.size} packet class names at $output")
    }

    private fun scanMinecraftInputs(inputs: List<Path>): Map<String, ClassInfo> {
        val inputDefinitions = inputs.map { input ->
            require(Files.exists(input)) { "Minecraft input does not exist: $input" }
            val definitions = if (Files.isDirectory(input)) {
                readDirectory(input)
            } else {
                require(Files.isRegularFile(input) && input.fileName.toString().endsWith(".jar", ignoreCase = true)) {
                    "Minecraft input must be a JAR or classes directory: $input"
                }
                readJar(input)
            }
            InputDefinitions(input, definitions)
        }

        val minecraftInputs = inputDefinitions.filter { (_, definitions) -> definitions.isNotEmpty() }
        val classes = linkedMapOf<String, ClassInfo>()
        for ((input, definitions) in minecraftInputs) {
            println("Scanning ${definitions.size} net/minecraft classes from $input")
            for ((name, info) in definitions) {
                val previous = classes.putIfAbsent(name, info)
                require(previous == null || previous.bytes.contentEquals(info.bytes)) {
                    "Conflicting class definition for ${name.replace('/', '.')} in Minecraft inputs (including $input)"
                }
            }
        }
        require(PACKET_INTERNAL_NAME in classes) {
            "None of the inputs contains $PACKET_INTERNAL_NAME.class; pass the Mojang-named Minecraft JAR or classes directory"
        }

        val root = classes[PACKET_INTERNAL_NAME]
            ?: error("Minecraft inputs did not provide $PACKET_INTERNAL_NAME")
        require(root.isInterface) {
            "Expected $PACKET_INTERNAL_NAME to be an interface, found a class in the supplied Minecraft inputs"
        }
        require(REQUIRED_PACKET_INTERNAL_NAME in classes) {
            "Mojang-named Minecraft input is missing required class $REQUIRED_PACKET_INTERNAL_NAME"
        }
        return classes
    }

    private fun readJar(jarPath: Path): Map<String, ClassInfo> {
        val result = linkedMapOf<String, ClassInfo>()
        JarFile(jarPath.toFile()).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.startsWith("net/minecraft/") || !entry.name.endsWith(".class") ||
                    entry.name.startsWith("META-INF/versions/")
                ) {
                    continue
                }
                val bytes = jar.getInputStream(entry).use { it.readBytes() }
                val info = parseClass(bytes, "${jarPath}!/${entry.name}") ?: continue
                if (info.name.startsWith("net/minecraft/")) {
                    addDefinition(result, info, "${jarPath}!/${entry.name}")
                }
            }
        }
        return result
    }

    private fun readDirectory(directory: Path): Map<String, ClassInfo> {
        val result = linkedMapOf<String, ClassInfo>()
        Files.walk(directory).use { paths ->
            val iterator = paths.filter {
                Files.isRegularFile(it) &&
                    directory.relativize(it).toString().replace('\\', '/').startsWith("net/minecraft/") &&
                    it.fileName.toString().endsWith(".class")
            }.iterator()
            while (iterator.hasNext()) {
                val path = iterator.next()
                val bytes = Files.readAllBytes(path)
                val info = parseClass(bytes, path.toString()) ?: continue
                if (info.name.startsWith("net/minecraft/")) {
                    addDefinition(result, info, path.toString())
                }
            }
        }
        return result
    }

    private fun addDefinition(target: MutableMap<String, ClassInfo>, info: ClassInfo, source: String) {
        val previous = target.putIfAbsent(info.name, info)
        require(previous == null || previous.bytes.contentEquals(info.bytes)) {
            "Conflicting class definitions for ${info.name.replace('/', '.')} within input $source"
        }
    }

    private fun parseClass(bytes: ByteArray, source: String): ClassInfo? {
        var info: ClassInfo? = null
        try {
            ClassReader(bytes).accept(object : ClassVisitor(Opcodes.ASM9) {
                private var name: String? = null
                private var access: Int = 0
                private var superName: String? = null
                private var interfaces: Array<out String> = emptyArray()
                private var hasEnclosingMethod = false
                private val innerClasses = mutableMapOf<String, InnerClassInfo>()

                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>,
                ) {
                    this.name = name
                    this.access = access
                    this.superName = superName
                    this.interfaces = interfaces
                }

                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    innerClasses[name] = InnerClassInfo(outerName, innerName, access)
                }

                override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
                    hasEnclosingMethod = true
                }

                override fun visitEnd() {
                    val className = name ?: throw IOException("Class file has no name")
                    info = ClassInfo(
                        name = className,
                        access = access,
                        superName = superName,
                        interfaces = interfaces.toList(),
                        innerClasses = innerClasses.toMap(),
                        hasEnclosingMethod = hasEnclosingMethod,
                        bytes = bytes,
                    )
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        } catch (error: Exception) {
            throw IllegalArgumentException("Failed to read class header from $source", error)
        }
        return info
    }

    private fun findConcretePackets(classes: Map<String, ClassInfo>): List<GeneratedPacket> {
        val packetCache = mutableMapOf<String, Boolean>()
        val packets = mutableListOf<GeneratedPacket>()
        for (info in classes.values.sortedBy(ClassInfo::name)) {
            if (info.isInterface || info.isAbstract || !implementsPacket(info.name, classes, packetCache, linkedSetOf())) {
                continue
            }
            val javaName = javaSourceName(info.name, classes, linkedSetOf())
            packets += GeneratedPacket(javaName, info.name.replace('/', '.'))
        }
        return packets.sortedBy(GeneratedPacket::javaName)
    }

    private fun implementsPacket(
        className: String,
        classes: Map<String, ClassInfo>,
        cache: MutableMap<String, Boolean>,
        visiting: MutableSet<String>,
    ): Boolean {
        if (className == PACKET_INTERNAL_NAME) return true
        cache[className]?.let { return it }
        if (!visiting.add(className)) {
            throw IllegalArgumentException("Cycle found in Minecraft class hierarchy at ${className.replace('/', '.')}")
        }
        val info = classes[className]
            ?: throw IllegalArgumentException("Missing Minecraft superclass or interface ${className.replace('/', '.')} needed to classify a packet")
        val parents = buildList {
            info.superName?.let(::add)
            addAll(info.interfaces)
        }
        val result = parents.any { parent ->
            when {
                parent == PACKET_INTERNAL_NAME -> true
                parent.startsWith("net/minecraft/") -> implementsPacket(parent, classes, cache, visiting)
                else -> false
            }
        }
        visiting.remove(className)
        cache[className] = result
        return result
    }

    private fun javaSourceName(
        internalName: String,
        classes: Map<String, ClassInfo>,
        visiting: MutableSet<String>,
    ): String {
        if (!visiting.add(internalName)) {
            throw IllegalArgumentException("Cycle found in nested class metadata at ${internalName.replace('/', '.')}")
        }
        val info = classes[internalName]
            ?: throw IllegalArgumentException("Missing class metadata for ${internalName.replace('/', '.')}")
        require(!info.hasEnclosingMethod) {
            "Concrete Packet ${internalName.replace('/', '.')} is a local or anonymous class and cannot be referenced in generated Java"
        }
        val inner = info.innerClasses[internalName]
        val sourceName = if (inner == null) {
            require(info.access and Opcodes.ACC_PUBLIC != 0) {
                "Concrete Packet ${internalName.replace('/', '.')} is not public"
            }
            internalName.replace('/', '.')
        } else {
            require(inner.outerName != null && inner.innerName != null) {
                "Concrete Packet ${internalName.replace('/', '.')} is an anonymous or local class and cannot be referenced in generated Java"
            }
            require(inner.access and Opcodes.ACC_PUBLIC != 0) {
                "Concrete Packet ${internalName.replace('/', '.')} is not public"
            }
            val outer = classes[inner.outerName]
                ?: throw IllegalArgumentException("Missing enclosing class ${inner.outerName.replace('/', '.')} for ${internalName.replace('/', '.')}")
            require(
                outer.access and Opcodes.ACC_PUBLIC != 0 ||
                    (outer.innerClasses[inner.outerName]?.access?.and(Opcodes.ACC_PUBLIC) ?: 0) != 0,
            ) {
                "Enclosing class ${inner.outerName.replace('/', '.')} for ${internalName.replace('/', '.')} is not public"
            }
            "${javaSourceName(inner.outerName, classes, visiting)}.${inner.innerName}"
        }
        visiting.remove(internalName)
        return sourceName
    }

    private fun generateSource(packets: List<GeneratedPacket>): String = buildString {
        appendLine("package $GENERATED_PACKAGE;")
        appendLine()
        appendLine("import java.util.Collections;")
        appendLine("import java.util.HashMap;")
        appendLine("import java.util.Map;")
        appendLine()
        appendLine("/** Generated from Mojang-mapped Minecraft class files. Do not edit. */")
        appendLine("public final class $GENERATED_CLASS {")
        appendLine("    private static final Map<Class<?>, String> NAMES;")
        appendLine()
        appendLine("    static {")
        appendLine("        Map<Class<?>, String> names = new HashMap<>(${packets.size});")
        for (packet in packets) {
            appendLine("        names.put(${packet.javaName}.class, \"${escapeJava(packet.binaryName)}\");")
        }
        appendLine("        NAMES = Collections.unmodifiableMap(names);")
        appendLine("    }")
        appendLine()
        appendLine("    private $GENERATED_CLASS() {")
        appendLine("    }")
        appendLine()
        appendLine("    public static String find(Class<?> type) {")
        appendLine("        return NAMES.get(type);")
        appendLine("    }")
        appendLine("}")
    }

    private fun escapeJava(value: String): String = buildString(value.length) {
        for (character in value) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private data class InputDefinitions(val path: Path, val definitions: Map<String, ClassInfo>)

    private data class ClassInfo(
        val name: String,
        val access: Int,
        val superName: String?,
        val interfaces: List<String>,
        val innerClasses: Map<String, InnerClassInfo>,
        val hasEnclosingMethod: Boolean,
        val bytes: ByteArray,
    ) {
        val isInterface: Boolean get() = access and Opcodes.ACC_INTERFACE != 0
        val isAbstract: Boolean get() = access and Opcodes.ACC_ABSTRACT != 0
    }

    private data class InnerClassInfo(val outerName: String?, val innerName: String?, val access: Int)
    private data class GeneratedPacket(val javaName: String, val binaryName: String)
}
