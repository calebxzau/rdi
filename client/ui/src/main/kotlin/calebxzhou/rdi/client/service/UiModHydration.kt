package calebxzhou.rdi.client.service

import calebxzau.rdi.client.modcatalog.ModCatalog
import calebxzau.rdi.client.lgr
import calebxzhou.rdi.client.model.UiMod
import calebxzhou.rdi.client.model.toUiMod
import calebxzhou.rdi.common.model.Mod
import calebxzhou.rdi.common.model.ModrinthProject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.jvm.JvmName

fun List<Mod>.toUiMods(): List<UiMod> = map { it.toUiMod() }

private val uiModResolvers: List<ModCardResolver> = listOf(
    CurseForgeCardResolver,
    ModrinthCardResolver
)

suspend fun List<Mod>.hydrateToUiMods(
    modCatalog: ModCatalog,
    modrinthProjects: List<ModrinthProject>? = null
): List<UiMod> = hydrateToUiModsInBatches(modCatalog, modrinthProjects).last()

@JvmName("hydrateExistingUiMods")
suspend fun List<UiMod>.hydrateToUiMods(
    modCatalog: ModCatalog,
    modrinthProjects: List<ModrinthProject>? = null
): List<UiMod> = hydrateToUiModsInBatches(modCatalog, modrinthProjects).last()

fun List<Mod>.hydrateToUiModsInBatches(
    modCatalog: ModCatalog,
    modrinthProjects: List<ModrinthProject>? = null
): Flow<List<UiMod>> = toUiMods().hydrateToUiModsInBatches(modCatalog, modrinthProjects)

@JvmName("hydrateExistingUiModsInBatches")
fun List<UiMod>.hydrateToUiModsInBatches(
    modCatalog: ModCatalog,
    modrinthProjects: List<ModrinthProject>? = null
): Flow<List<UiMod>> = flow {
    val source = this@hydrateToUiModsInBatches
    emit(source)
    if (source.isEmpty()) return@flow

    val resolveContext = ModCardResolveContext(modCatalog, modrinthProjects)
    val localCardMap = LocalModCardResolver.resolve(source, resolveContext)
    val cardMap = LinkedHashMap<String, Mod.CardVo>().apply {
        source.mapNotNull { uiMod ->
            uiMod.card?.let { uiMod.mod.projectKey() to it }
        }.forEach { (key, card) -> put(key, card) }
        localCardMap.forEach { (key, card) -> put(key, card.withVersionFallback(get(key))) }
    }

    emit(source.withCards(cardMap))

    supervisorScope {
        val results = Channel<RemoteCardResolution>(capacity = uiModResolvers.size)
        val jobs = uiModResolvers.map { resolver ->
            launch {
                val result = try {
                    Result.success(resolver.resolve(source, resolveContext))
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (cause: Throwable) {
                    Result.failure(cause)
                }
                results.send(RemoteCardResolution(resolver, result))
            }
        }

        repeat(jobs.size) {
            val resolution = results.receive()
            resolution.result
                .onSuccess { remoteCardMap ->
                    remoteCardMap.forEach { (key, remoteCard) ->
                        val merged = localCardMap[key]?.mergeLocalFirst(remoteCard) ?: remoteCard
                        cardMap[key] = merged.withVersionFallback(cardMap[key])
                    }
                }
                .onFailure { cause ->
                    lgr.warn(cause) {
                        "补充${resolution.resolver.platform} Mod信息失败，保留当前卡片"
                    }
                }
            emit(source.withCards(cardMap))
        }

        jobs.joinAll()
        results.close()
    }
}

/** A later card without a version keeps the one already known for the same mod. */
private fun Mod.CardVo.withVersionFallback(previous: Mod.CardVo?): Mod.CardVo =
    if (version == null && previous?.version != null) copy(version = previous.version) else this

private data class RemoteCardResolution(
    val resolver: ModCardResolver,
    val result: Result<Map<String, Mod.CardVo>>
)

private fun List<UiMod>.withCards(cardMap: Map<String, Mod.CardVo>): List<UiMod> = map { uiMod ->
    uiMod.copy(
        card = cardMap[uiMod.mod.projectKey()]?.copy(side = uiMod.side)
            ?: uiMod.card?.copy(side = uiMod.side)
    )
}

