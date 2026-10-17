package calebxzhou.rdi.client.ui.comp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import calebxzau.rdi.client.lgr
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzau.rdi.client.ui.RenderFeatures
import calebxzau.rdi.client.ui.decodeImageBitmap
import calebxzau.rdi.playermodel.PlayerModelRenderer
import calebxzau.rdi.playermodel.PlayerModelTexture
import calebxzau.rdi.client.ui.themeNow
import calebxzhou.rdi.common.net.httpRequest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenGL player model renderer.
 * Input: a standard Minecraft skin (64x64 or 64x32) and optional cape.
 */

@Composable
fun PlayerModel(
    skin: ImageBitmap?,
    cape: ImageBitmap? = null,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    autoRotate: Boolean = true,
    animateWalk: Boolean = true,
    showOuterLayer: Boolean = true,
    isSlim: Boolean? = null,
    maxRenderSide: Int = 480,
    noControl: Boolean = false
) {
    if (!RenderFeatures.available) {
        Box(modifier = modifier.background(backgroundColor), contentAlignment = Alignment.Center) {
            PlayerHead(skinImage = skin, modifier = Modifier.fillMaxSize(0.5f).aspectRatio(1f))
        }
        return
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var autoRotateEnabled by remember(skin, cape, autoRotate) { mutableStateOf(autoRotate) }
    var walkEnabled by remember(skin, cape, animateWalk) { mutableStateOf(animateWalk) }
    val skinPixels = remember(skin, isSlim) { skin?.let { PlayerModelTexture.skin(it, isSlim) } }
    val capePixels = remember(cape) { cape?.let(PlayerModelTexture::cape) }
    val controller = remember { PlayerModelRenderer.openSession() }
    val frame by controller.frame.collectAsState()
    val failure by controller.failure.collectAsState()

    LaunchedEffect(
        controller,
        skinPixels,
        capePixels,
        autoRotateEnabled,
        walkEnabled,
        showOuterLayer,
        maxRenderSide,
        viewport
    ) {
        controller.update(
            skin = skinPixels,
            cape = capePixels,
            autoRotate = autoRotateEnabled,
            animateWalk = walkEnabled,
            showOuterLayer = showOuterLayer,
            maxRenderSide = maxRenderSide
        )
        controller.resize(viewport.width, viewport.height)
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }

    Box(
        modifier = modifier
            .background(backgroundColor)
            .onSizeChanged { viewport = it }
            .pointerInput(controller, skinPixels) {
                if (skinPixels == null) return@pointerInput
                detectDragGestures(
                    onDragStart = { controller.startDragging() },
                    onDragEnd = { controller.stopDragging() },
                    onDragCancel = { controller.stopDragging() }
                ) { change, dragAmount ->
                    change.consume()
                    controller.drag(dragAmount)
                }
            }
    ) {
        when {
            failure != null -> Text(
                text = failure!!,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center)
            )

            frame != null -> {
                Image(
                    bitmap = frame!!,
                    contentDescription = "Player Model",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )
                if (!noControl) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircleIconButton(
                            icon = "\uDB83\uDD98",
                            tooltip = if (autoRotateEnabled) "自动旋转：开" else "自动旋转：关",
                            bgColor = if (autoRotateEnabled) themeNow.primary else themeNow.surfaceVariant,
                            size = 30.dp,
                            showText = false
                        ) {
                            autoRotateEnabled = !autoRotateEnabled
                        }
                        CircleIconButton(
                            icon = "\uEE1D",
                            tooltip = if (walkEnabled) "走路动画：开" else "走路动画：关",
                            bgColor = if (walkEnabled) themeNow.primary else themeNow.surfaceVariant,
                            size = 30.dp,
                            showText = false
                        ) {
                            walkEnabled = !walkEnabled
                        }
                        CircleIconButton(
                            icon = "\uDB81\uDC50",
                            tooltip = "重置视角",
                            bgColor = MaterialTheme.colorScheme.primary,
                            size = 30.dp,
                            showText = false,
                            onClick = controller::resetView
                        )
                    }
                }
            }

            else -> Text(
                text = "皮肤加载中...",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
fun PlayerModel(
    skinUrl: String,
    capeUrl: String? = null,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    autoRotate: Boolean = true,
    animateWalk: Boolean = true,
    showOuterLayer: Boolean = true,
    isSlim: Boolean? = null,
    maxRenderSide: Int = 480,
    noControl: Boolean = false
) {
    val skin = produceState<ImageBitmap?>(initialValue = null, skinUrl) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val response = httpRequest { url(skinUrl) }
                if (!response.status.isSuccess()) return@runCatching null
                decodeImageBitmap(response.bodyAsBytes()).getOrThrow()
            }.onFailure { lgr.warn(it) { "玩家皮肤图片解码失败" } }
                .getOrNull()
        }
    }.value
    val cape = produceState<ImageBitmap?>(initialValue = null, capeUrl) {
        if (capeUrl.isNullOrBlank()) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val response = httpRequest { url(capeUrl) }
                if (!response.status.isSuccess()) return@runCatching null
                decodeImageBitmap(response.bodyAsBytes()).getOrThrow()
            }.onFailure { lgr.warn(it) { "玩家披风图片解码失败" } }
                .getOrNull()
        }
    }.value
    PlayerModel(
        skin = skin,
        cape = cape,
        modifier = modifier,
        backgroundColor = backgroundColor,
        autoRotate = autoRotate,
        animateWalk = animateWalk,
        showOuterLayer = showOuterLayer,
        isSlim = isSlim,
        maxRenderSide = maxRenderSide,
        noControl = noControl
    )
}
