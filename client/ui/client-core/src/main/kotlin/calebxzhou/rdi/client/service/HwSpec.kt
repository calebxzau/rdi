package calebxzhou.rdi.client.service


import calebxzhou.rdi.common.model.HwSpec
import calebxzhou.rdi.common.model.HwSpec.*
import calebxzhou.rdi.common.serdesJson
import oshi.SystemInfo
import oshi.util.EdidUtil

/**
 * calebxzhou @ 2026-02-18 12:54
 */
private val hwSpecCacheLock = Any()
@Volatile
private var cachedHwSpecJson: String? = null

fun fetchHwSpec(): HwSpec{
    val systemInfo = SystemInfo()
    val hal = systemInfo.hardware
    val model = "${hal.computerSystem.manufacturer} ${hal.computerSystem.model}"
    val os = systemInfo.operatingSystem.run { "$manufacturer $family $versionInfo" }
    val cpu = hal.processor.run {
        Cpu(
            //amd会在后面加一堆空格 去掉
            processorIdentifier.name.replace(Regex(" +")," "),
            physicalProcessorCount,
            logicalProcessorCount,
            processorIdentifier.vendorFreq
        )
    }
    val gpu = hal.graphicsCards.map {
        Gpu(it.name, it.vRam)
    }
    val mem = hal.memory.physicalMemory.map {
        Memory(it.capacity, it.memoryType, it.clockSpeed)
    }
    val disk = hal.diskStores.map {
        Disk(it.name, it.size, it.model)
    }


    // Get display resolutions from Windows.
    val displayModes = getDisplayModes()

    val display = hal.displays.map { it.edid }.mapIndexed { i, edid ->
        val name = EdidUtil.getDescriptors(edid).firstNotNullOfOrNull {
            when (EdidUtil.getDescriptorType(it)) {
                0xfc -> EdidUtil.getDescriptorText(it)
                else -> null
            }
        } ?: ""
        val hcm = EdidUtil.getHcm(edid)
        val vcm = EdidUtil.getVcm(edid)

        // Try to get mode from GraphicsEnvironment first, then fallback to GLFW


        Display(name, vcm, hcm)
    }
    return HwSpec(
        model,
        os,
        cpu,
        gpu,
        mem,
        disk,
        display,
        displayModes
    )
}

fun getCachedOrFetchHwSpecJson(): String {
    cachedHwSpecJson?.let { return it }
    return synchronized(hwSpecCacheLock) {
        cachedHwSpecJson ?: serdesJson.encodeToString(fetchHwSpec()).also {
            cachedHwSpecJson = it
        }
    }
}

fun warmUpHwSpecCache() {
    runCatching { getCachedOrFetchHwSpecJson() }
}

