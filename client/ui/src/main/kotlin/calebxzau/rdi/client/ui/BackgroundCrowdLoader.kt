package calebxzau.rdi.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import calebxzhou.rdi.client.net.server
import calebxzhou.rdi.client.service.HttpImageState
import calebxzau.rdi.bgrenderer.BackgroundCrowdPlayer
import calebxzau.rdi.bgrenderer.BackgroundRenderSession
import calebxzau.rdi.bgrenderer.MAX_BACKGROUND_PLAYERS
import calebxzau.rdi.client.lgr
import calebxzau.rdi.playermodel.core.PlayerSkinTexture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

@Composable
internal fun BackgroundCrowdEffect(renderer: BackgroundRenderSession) {
    val api = server
    val players by produceState<List<BackgroundCrowdPlayer>>(emptyList(), renderer, api.hqUrl) {
        value = emptyList()
        val fallback = withContext(Dispatchers.IO) {
            runCatching {
                val skin = loadImageBitmap("assets/skins/steve.png").getOrThrow().toBackgroundCrowdSkin()
                BackgroundCrowdPlayer("builtin:steve", skin)
            }.onFailure { lgr.error(it) { "背景人群默认皮肤加载失败" } }.getOrNull()
        }
        value = listOfNotNull(fallback)
        val urls = try {
            withTimeout(15_000) {
                val response = api.makeRequest<List<String>>("player/unique-skin-list")
                check(response.ok) { response.msg }
                requireNotNull(response.data) { "皮肤列表响应缺少数据" }
                    .map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_BACKGROUND_PLAYERS)
            }
        } catch (timeout: TimeoutCancellationException) {
            lgr.warn(timeout) { "背景人群皮肤列表加载超时，将使用Steve" }
            return@produceState
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            lgr.warn(error) { "背景人群皮肤列表加载失败，将使用Steve" }
            return@produceState
        }
        val loaded = mutableListOf<BackgroundCrowdPlayer>()
        val downloads = Semaphore(4)
        coroutineScope {
            urls.forEach { url ->
                launch {
                    val player = downloads.withPermit {
                        try {
                            withTimeout(15_000) {
                                withContext(Dispatchers.IO) {
                                    val bitmap = requireNotNull(HttpImageState.fetch(url).bitmap) { "皮肤图片下载或解码失败" }
                                    BackgroundCrowdPlayer(url, bitmap.toBackgroundCrowdSkin())
                                }
                            }
                        } catch (timeout: TimeoutCancellationException) {
                            lgr.warn(timeout) { "背景人群跳过超时皮肤: ${url}" }
                            null
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (error: Exception) {
                            lgr.warn(error) { "背景人群跳过无效皮肤: ${url}" }
                            null
                        }
                    }
                    if (player != null) {
                        // These updates resume on the produceState context, not the download threads.
                        loaded += player
                        value = loaded.toList()
                    }
                }
            }
        }
    }
    LaunchedEffect(renderer, players) { renderer.setCrowd(players) }
}

/** Normalize HD skins to the shared model's 64px UV grid before GPU upload. */
internal fun ImageBitmap.toBackgroundCrowdSkin(): PlayerSkinTexture {
    require(width in 64..2048 && width % 64 == 0 && (height == width || height * 2 == width)) {
        "不支持的皮肤尺寸: ${width}x${height}"
    }
    val targetHeight = if (height == width) 64 else 32
    val source = toPixelMap()
    val rgba = ByteArray(64 * targetHeight * 4)
    for (y in 0 until targetHeight) for (x in 0 until 64) {
        val color = source[x * width / 64, y * height / targetHeight]
        val offset = (y * 64 + x) * 4
        rgba[offset] = (color.red * 255).roundToInt().toByte()
        rgba[offset + 1] = (color.green * 255).roundToInt().toByte()
        rgba[offset + 2] = (color.blue * 255).roundToInt().toByte()
        rgba[offset + 3] = (color.alpha * 255).roundToInt().toByte()
    }
    return PlayerSkinTexture(64, targetHeight, rgba)
}
