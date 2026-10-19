package calebxzhou.rdi.client.service

import calebxzhou.rdi.client.database.ModpackLaunchOptionsRecord
import calebxzhou.rdi.client.database.ModpackLaunchOptionsStore
import calebxzau.rdi.client.service.parseModpackJvmParams

data class ModpackLaunchSnapshot(
    val javaPath: String?,
    val maxMemoryMb: Int?,
    val jdwpJvmArg: String?,
    val customJvmArgs: List<String>,
    val forgeguardDisabled: Boolean,
)

object ModpackLaunchOptionsService {
    private lateinit var store: ModpackLaunchOptionsStore

    fun initialize(store: ModpackLaunchOptionsStore) {
        this.store = store
    }

    suspend fun find(versionId: String): Result<ModpackLaunchOptionsRecord?> =
        store.find(versionId)

    suspend fun save(record: ModpackLaunchOptionsRecord): Result<Unit> =
        store.upsert(record)

    suspend fun delete(versionId: String): Result<Unit> =
        store.delete(versionId)

    suspend fun loadSnapshot(versionId: String): Result<ModpackLaunchSnapshot> = runCatching {
        val record = store.find(versionId).getOrThrow()
        if (record == null) {
            return@runCatching ModpackLaunchSnapshot(
                javaPath = null,
                maxMemoryMb = null,
                jdwpJvmArg = null,
                customJvmArgs = emptyList(),
                forgeguardDisabled = false,
            )
        }
        require(record.javaPath?.isNotBlank() != false) { "整合包自定义JDK路径为空" }
        require(record.maxMemoryMb == null || record.maxMemoryMb!! > 4096) {
            "整合包自定义最大内存无效"
        }
        val jdwpJvmArg = if (record.jdwpEnabled) {
            val param = record.jdwpParam?.trim().orEmpty()
            require(param.isNotBlank()) { "整合包JDWP参数为空" }
            require(!param.contains('\n') && !param.contains('\r')) { "整合包JDWP参数包含换行" }
            require(!param.startsWith("-agentlib:jdwp=")) { "整合包JDWP参数格式无效" }
            "-agentlib:jdwp=$param"
        } else {
            null
        }
        val customJvmArgs = parseModpackJvmParams(record.customJvmParams).getOrThrow()
        ModpackLaunchSnapshot(
            javaPath = record.javaPath,
            maxMemoryMb = record.maxMemoryMb,
            jdwpJvmArg = jdwpJvmArg,
            customJvmArgs = customJvmArgs,
            forgeguardDisabled = record.forgeguardDisabled,
        )
    }
}
