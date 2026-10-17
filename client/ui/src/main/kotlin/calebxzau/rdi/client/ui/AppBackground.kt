package calebxzau.rdi.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import calebxzau.rdi.bgrenderer.BackgroundRenderSession
import calebxzau.rdi.bgrenderer.BackgroundRenderer
import calebxzau.rdi.bgrenderer.BackgroundPlayerAppearance
import calebxzhou.rdi.client.auth.AccountSessionStore
import calebxzau.rdi.client.lgr
import calebxzhou.rdi.client.service.HttpImageState
import calebxzau.rdi.playermodel.core.PlayerSkinTexture
import calebxzau.rdi.playermodel.core.detectSlimSkin
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val LocalAppBackgroundPainter = staticCompositionLocalOf<Painter> {
    error("LocalAppBackgroundPainter is not provided")
}

/** null when [RenderFeatures.available] is false; the static background is shown instead. */
val LocalBackgroundRenderer = staticCompositionLocalOf<BackgroundRenderSession?> {
    error("LocalBackgroundRenderer is not provided")
}

/** App背景的haze来源，玻璃控件用它模糊/折射背景。 */
val LocalAppBackgroundHazeState = staticCompositionLocalOf<HazeState> {
    error("LocalAppBackgroundHazeState is not provided")
}

@Composable
fun AppBackgroundProvider(content: @Composable () -> Unit) {
    val background: Painter = remember {
        loadImageBitmap("assets/bg.avif").fold(
            onSuccess = { BitmapPainter(it) },
            onFailure = {
                lgr.error(it) { "AVIF背景图解码失败，将使用纯白背景" }
                ColorPainter(Color.White)
            }
        )
    }

    val renderer = remember { if (RenderFeatures.available) BackgroundRenderer.openSession() else null }
    val hazeState = rememberHazeState()
    if (renderer != null) {
        BackgroundPlayerAppearanceEffect(renderer)
        DisposableEffect(renderer) {
            onDispose { renderer.close() }
        }
    }
    CompositionLocalProvider(
        LocalAppBackgroundPainter provides background,
        LocalBackgroundRenderer provides renderer,
        LocalAppBackgroundHazeState provides hazeState,
        content = content
    )
}

/** Keeps the background player's skin and cape in sync with the logged-in account. */
@Composable
private fun BackgroundPlayerAppearanceEffect(renderer: BackgroundRenderSession) {
    val account by AccountSessionStore.account.collectAsState()
    val fallbackSkin = remember {
        loadImageBitmap("assets/skins/steve.png").onFailure { error ->
            lgr.error(error) { "Steve皮肤fallback加载失败" }
        }.getOrNull()
    }
    val skinUrl = account.cloth.skin.orEmpty()
    val capeUrl = account.cloth.cape.orEmpty()
    val remoteSkin by produceState<ImageBitmap?>(null, skinUrl) {
        value = null
        if (skinUrl.isNotBlank()) {
            val fetched = withContext(Dispatchers.IO) { HttpImageState.fetch(skinUrl) }
            if (fetched.bitmap != null) value = fetched.bitmap else lgr.warn { "背景玩家远程皮肤加载失败，将使用Steve皮肤" }
        }
    }
    val skin = remoteSkin ?: fallbackSkin
    val cape by produceState<ImageBitmap?>(null, capeUrl) {
        value = if (capeUrl.isBlank()) null else withContext(Dispatchers.IO) {
            HttpImageState.fetch(capeUrl).also { result ->
                if (result.bitmap == null) lgr.warn { "背景玩家披风加载失败" }
            }.bitmap
        }
    }
    val coreSkin = remember(skin, remoteSkin, account.cloth.isSlim) {
        skin?.toCoreSkin(remoteSkin?.let { account.cloth.isSlim } ?: false)
    }
    val coreCape = remember(cape) { cape?.toCoreSkin() }
    LaunchedEffect(renderer, coreSkin, coreCape) {
        renderer.setPlayerAppearance(coreSkin?.let { BackgroundPlayerAppearance(it, coreCape) })
    }
}

private fun ImageBitmap.toCoreSkin(slim: Boolean? = null): PlayerSkinTexture {
    val pixels = toPixelMap()
    val rgba = ByteArray(width * height * 4)
    var offset = 0
    for (y in 0 until height) for (x in 0 until width) {
        val color = pixels[x, y]
        rgba[offset++] = (color.red * 255).roundToInt().toByte()
        rgba[offset++] = (color.green * 255).roundToInt().toByte()
        rgba[offset++] = (color.blue * 255).roundToInt().toByte()
        rgba[offset++] = (color.alpha * 255).roundToInt().toByte()
    }
    return PlayerSkinTexture(width, height, rgba, slim ?: detectSlimSkin(width, height, rgba))
}

@Composable
fun AppBackgroundImage(active: Boolean, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val fallback = LocalAppBackgroundPainter.current
    val renderer = LocalBackgroundRenderer.current
    val hazeState = LocalAppBackgroundHazeState.current
    val painter = if (renderer == null) {
        fallback
    } else {
        val frame by renderer.frame.collectAsState()
        val failure by renderer.failure.collectAsState()
        val lowPerformance by renderer.lowPerformance.collectAsState()
        LaunchedEffect(renderer, active) {
            renderer.setActive(active)
        }
        if (failure == null && !lowPerformance) frame ?: fallback else fallback
    }
    Image(
        painter = painter,
        contentDescription = "rdi5 background",
        modifier = modifier.hazeSource(hazeState),
        contentScale = ContentScale.Crop
    )
}
