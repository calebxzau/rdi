package calebxzau.rdi.mc.client.dm

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.RegistryLayer
import net.neoforged.fml.ModList
import java.security.MessageDigest
import java.util.UUID

/**
 * Registry contents hashed per namespace, matching dm-master `mods.rs`. [registries] covers the
 * static registries NeoForge syncs to clients, so it follows NeoForge's own join check and leaves
 * out client-only mods; [world] covers the data pack registries (biomes, dimensions, …), which only
 * exist while a world is open. [labels] name the mods in messages and are not compared.
 */
data class DmModFingerprint(
    val registries: Part,
    val world: Part?,
    val labels: Map<String, String>,
) {
    data class Part(val overall: String, val namespaces: Map<String, String>)

    /** Namespaces the room has and this player lacks, has extra, or has with other contents. */
    data class Difference(val missing: List<String>, val extra: List<String>, val changed: List<String>) {
        val isEmpty: Boolean get() = missing.isEmpty() && extra.isEmpty() && changed.isEmpty()
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("version", VERSION)
        add("registries", registries.toJson())
        world?.let { add("world", it.toJson()) }
        add("labels", JsonObject().apply { labels.toSortedMap().forEach { (namespace, label) -> addProperty(namespace, label) } })
    }

    companion object {
        const val VERSION = 1

        /** Hashes `registry entry` lines grouped by the entry's namespace. */
        fun part(entries: Iterable<Pair<String, String>>): Part {
            val namespaces = entries.groupBy({ (_, entry) -> entry.substringBefore(':') }) { (registry, entry) -> "$registry $entry\n" }
                .mapValues { (_, lines) -> sha1(lines.sorted().joinToString("")) }
                .toSortedMap()
            return Part(overallOf(namespaces), namespaces)
        }

        fun overallOf(namespaces: Map<String, String>): String =
            sha1(namespaces.toSortedMap().entries.joinToString("") { (namespace, hash) -> "$namespace=$hash\n" })

        /** Compares the static registries, and the world registries when both sides have them. */
        fun diff(room: DmModFingerprint, local: DmModFingerprint): Difference {
            val parts = listOfNotNull(room.registries to local.registries, room.world?.let { world -> local.world?.let { world to it } })
            val missing = sortedSetOf<String>()
            val extra = sortedSetOf<String>()
            val changed = sortedSetOf<String>()
            for ((theirs, ours) in parts) {
                missing += theirs.namespaces.keys - ours.namespaces.keys
                extra += ours.namespaces.keys - theirs.namespaces.keys
                changed += theirs.namespaces.filter { (namespace, hash) -> ours.namespaces[namespace]?.let { it != hash } == true }.keys
            }
            return Difference(missing.toList(), extra.toList(), changed.toList())
        }

        /** "缺少：A、B；多出：C；内容不同：D", naming mods by either side's label. */
        fun describe(difference: Difference, room: DmModFingerprint, local: DmModFingerprint): String {
            fun names(namespaces: List<String>): String {
                val shown = namespaces.take(MAX_NAMED).joinToString("、") { room.labels[it] ?: local.labels[it] ?: it }
                return if (namespaces.size > MAX_NAMED) "${shown}等${namespaces.size}个" else shown
            }
            return listOfNotNull(
                difference.missing.takeIf { it.isNotEmpty() }?.let { "缺少：${names(it)}" },
                difference.extra.takeIf { it.isNotEmpty() }?.let { "多出：${names(it)}" },
                difference.changed.takeIf { it.isNotEmpty() }?.let { "版本不同：${names(it)}" },
            ).joinToString("；")
        }

        fun fromJson(json: JsonObject): DmModFingerprint {
            require(json.get("version")?.asInt == VERSION) { "不支持的Mod指纹版本" }
            fun part(value: JsonObject) = Part(
                value.get("overall").asString,
                value.getAsJsonObject("namespaces").entrySet().associate { (namespace, hash) -> namespace to hash.asString }.toSortedMap(),
            )
            return DmModFingerprint(
                part(json.getAsJsonObject("registries")),
                json.getAsJsonObject("world")?.let(::part),
                json.getAsJsonObject("labels")?.entrySet()?.associate { (namespace, label) -> namespace to label.asString } ?: emptyMap(),
            )
        }

        private const val MAX_NAMED = 6

        private fun Part.toJson() = JsonObject().apply {
            addProperty("overall", overall)
            add("namespaces", JsonObject().apply { namespaces.toSortedMap().forEach { (namespace, hash) -> addProperty(namespace, hash) } })
        }

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

/** The room's fingerprint as dm-master records it. */
data class DmRoomMods(val fingerprint: DmModFingerprint, val updatedBy: UUID?)

/** Reads this game's registries; kept apart from [DmModFingerprint] so tests never load them. */
object DmModFingerprints {
    @Volatile private var cachedRegistries: DmModFingerprint.Part? = null

    /**
     * The static part, taken once at the first title screen: while connected to a server the
     * synced registries hold that server's entries instead of this game's.
     */
    fun registries(): DmModFingerprint.Part = cachedRegistries ?: synchronized(this) {
        cachedRegistries ?: DmModFingerprint.part(
            BuiltInRegistries.REGISTRY.filter { it.doesSync() }.flatMap { registry ->
                val name = registry.key().location().toString()
                registry.keySet().map { name to it.toString() }
            },
        ).also { cachedRegistries = it }
    }

    /** Without [server] only the static part; with it, also the open world's data pack registries. */
    fun local(server: MinecraftServer?): DmModFingerprint {
        val registries = registries()
        val world = server?.let {
            val layers = it.registries()
            DmModFingerprint.part(
                listOf(RegistryLayer.WORLDGEN, RegistryLayer.DIMENSIONS).flatMap { layer ->
                    layers.getLayer(layer).registries().toList().flatMap { entry ->
                        val name = entry.key().location().toString()
                        entry.value().keySet().map { key -> name to key.toString() }
                    }
                },
            )
        }
        val namespaces = registries.namespaces.keys + world?.namespaces?.keys.orEmpty()
        val labels = namespaces.mapNotNull { namespace ->
            ModList.get().getModContainerById(namespace).map { namespace to "${it.modInfo.displayName} ${it.modInfo.version}" }.orElse(null)
        }.toMap()
        return DmModFingerprint(registries, world, labels)
    }

    /** The player's RDI id, recorded as who created the room or last changed its mods. */
    fun playerId(): UUID = Minecraft.getInstance().user.profileId
}
