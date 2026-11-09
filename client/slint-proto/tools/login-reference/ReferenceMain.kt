package calebxzhou.rdi.client.ui.screen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import calebxzau.rdi.client.ui.RTheme
fun main(args: Array<String>) {
    System.setProperty("b0.fixture", args[0])
    val scale = args[1].toFloat()
    application {
        Window(onCloseRequest = ::exitApplication, title = "B0 Compose Reference", undecorated = true,
            state = rememberWindowState(width = (1280 * scale).dp, height = (720 * scale).dp)) {
            CompositionLocalProvider(LocalDensity provides Density(scale)) {
                RTheme { Box(Modifier.fillMaxSize().background(Color(0xffc4d6ca))) { LoginReferenceScreen() } }
            }
        }
    }
}
