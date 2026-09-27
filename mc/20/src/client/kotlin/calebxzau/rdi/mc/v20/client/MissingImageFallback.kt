package calebxzau.rdi.mc.v20.client

import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.AbstractPackResources
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.IoSupplier
import net.minecraft.server.packs.resources.Resource
import net.minecraft.server.packs.resources.ResourceProvider
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object MissingImageFallback {
    private val logger = LoggerFactory.getLogger("RDI Missing Image")
    private val reportedLocations = ConcurrentHashMap.newKeySet<ResourceLocation>()

    // RGBA PNG: one fully transparent pixel. Each read owns its stream and decoded image.
    private val transparentPng = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4nGNgAAIAAAUAAXpeqz8AAAAASUVORK5CYII="
    )
    // Used only as provenance for synthetic Resources; never registered as a resource pack.
    private val fallbackPack = object : AbstractPackResources("rdi:missing_image", true) {
        override fun getRootResource(vararg elements: String): IoSupplier<InputStream>? = null

        override fun getResource(type: PackType, location: ResourceLocation): IoSupplier<InputStream>? = null

        override fun listResources(
            type: PackType,
            namespace: String,
            path: String,
            output: PackResources.ResourceOutput
        ) = Unit

        override fun getNamespaces(type: PackType): Set<String> = emptySet()

        override fun close() = Unit
    }

    @JvmStatic
    @Throws(FileNotFoundException::class)
    fun getResourceOrThrow(provider: ResourceProvider, location: ResourceLocation): Resource {
        val resource = provider.getResource(location)
        if (resource.isPresent) return resource.get()

        val error = FileNotFoundException(location.toString())
        val path = location.path
        if (!path.endsWith(".png") && !path.endsWith(".avif")) throw error

        if (reportedLocations.add(location)) {
            logger.error("图片资源不存在：{}，使用透明1×1图片（同一资源仅记录一次）", location, error)
        }
        return Resource(fallbackPack, IoSupplier { ByteArrayInputStream(transparentPng) })
    }
}
