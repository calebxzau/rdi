package calebxzhou.rdi.client.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import calebxzau.rdi.client.ui.CircleIconButton
import calebxzhou.rdi.client.Const
import calebxzau.rdi.client.ui.CodeFontFamily
import calebxzau.rdi.client.ui.MaxBox
import calebxzau.rdi.client.ui.ScreenContentSize
import calebxzau.rdi.client.ui.ScreenContentSurface
import calebxzau.rdi.client.ui.Space8w
import calebxzau.rdi.client.ui.asIconText
import calebxzhou.rdi.client.auth.LocalCredentials
import calebxzhou.rdi.client.net.RServer
import calebxzhou.rdi.client.service.PlayerService
import calebxzau.rdi.client.logic.LoginController
import calebxzhou.rdi.client.ui.*
import calebxzhou.rdi.common.DEBUG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * calebxzhou @ 2026-01-14 16:45
 */

@Composable
fun LoginScreen(
    onLoginSuccess: (() -> Unit)? = null,
    onOpenRegister: ((Boolean) -> Unit)? = null,
    onOpenResetPassword: (() -> Unit)? = null
) {
    val routeState by RServer.routeState.collectAsState()
    var showPassword by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val loginController = remember { LoginController { account, password -> PlayerService.login(account, password).map { Unit } } }
    val creds = remember { LocalCredentials.read() }
    val storedAccounts = remember(creds.loginInfos) {
        creds.loginInfos.entries.sortedByDescending { it.value.lastLoggedTime }
    }
    val qqState = rememberTextFieldState()
    val pwdState = rememberTextFieldState()
    var submitting by remember { mutableStateOf(false) }
    var showAccounts by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf<String?>(null) }
    var showMsAccountDialog by remember { mutableStateOf(false) }

    fun attemptLogin(
        qq: String = qqState.text.toString(),
        pwd: String = pwdState.text.toString(),
    ) {
        if (qq.isBlank() || pwd.isBlank()) {
            loginError = "未填写完整"
            return
        }
        if (submitting) return
        submitting = true
        loginError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                loginController.login(qq, pwd)
            }
            submitting = false
            result.onFailure {
                loginError = it.message ?: "登录失败"
            }.onSuccess {
                onLoginSuccess?.invoke()
            }
        }
    }

    LaunchedEffect(Unit) {
        creds.lastLogged?.let {
            qqState.setTextAndPlaceCursorAtEnd(it.qq)
            pwdState.setTextAndPlaceCursorAtEnd(it.pwd)
        }
        creds.autoLoginAccount?.let {
            attemptLogin(it.qq, it.pwd)
        }
    }
    MaxBox {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.2f))
            )
            val isPortrait = maxHeight > maxWidth
            val compactHeight = maxHeight < 650.dp
            val tinyHeight = maxHeight < 560.dp
            val outerPadding = if (compactHeight) PaddingValues(12.dp) else PaddingValues(18.dp)
            val rootSpacing = when {
                tinyHeight -> 8.dp
                compactHeight -> 10.dp
                else -> 20.dp
            }
            val formSpacing = when {
                tinyHeight -> 6.dp
                compactHeight -> 8.dp
                isPortrait -> 10.dp
                else -> 12.dp
            }
            val formWidthFraction = if (isPortrait) 1f else 0.82f
            val fieldShape = RoundedCornerShape(if (isPortrait) 28.dp else 36.dp)

            ScreenContentSurface(size = ScreenContentSize.SMALL) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(outerPadding),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "RDi",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(rootSpacing))

                        Column(
                            modifier = Modifier.fillMaxWidth(formWidthFraction),
                            verticalArrangement = Arrangement.spacedBy(formSpacing)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    state = qqState,
                                    shape = fieldShape,
                                    label = if (qqState.text.isBlank()) {
                                        { Text("RDID/QQ号") }
                                    } else {
                                        null
                                    },
                                    lineLimits = TextFieldLineLimits.SingleLine,
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .onKeyEvent { event ->
                                            if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                                attemptLogin()
                                                true
                                            } else {
                                                false
                                            }
                                        },
                                    trailingIcon = {
                                        TextButton(onClick = { showAccounts = true }) {
                                            Text("▼")
                                        }
                                    },
                                )
                                DropdownMenu(
                                    expanded = showAccounts,
                                    onDismissRequest = { showAccounts = false }
                                ) {
                                    storedAccounts.forEach { entry ->
                                        val info = entry.value
                                        DropdownMenuItem(
                                            text = { Text("${info.name} (${info.qq})") },
                                            onClick = {
                                                qqState.setTextAndPlaceCursorAtEnd(info.qq)
                                                pwdState.setTextAndPlaceCursorAtEnd(info.pwd)
                                                showAccounts = false
                                            }
                                        )
                                    }
                                    if (storedAccounts.isEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("暂无历史账号") },
                                            onClick = { showAccounts = false }
                                        )
                                    }
                                }
                            }
                            OutlinedSecureTextField(
                                state = pwdState,
                                shape = fieldShape,
                                label = if (pwdState.text.isBlank()) {
                                    { Text("密码") }
                                } else {
                                    null
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .onKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyUp && event.key == Key.Enter) {
                                            attemptLogin()
                                            true
                                        } else {
                                            false
                                        }
                                    },
                                textObfuscationMode = if (showPassword) {
                                    TextObfuscationMode.Visible
                                } else {
                                    TextObfuscationMode.RevealLastTyped
                                },
                                trailingIcon = {
                                    Text(
                                        text = "\uDB80\uDE08".asIconText,
                                        style = MaterialTheme.typography.titleLarge.copy(
                                            fontFamily = CodeFontFamily,
                                            fontSize = 20.sp
                                        ),
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .clickable { showPassword = !showPassword }
                                    )
                                }
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircleIconButton(
                                    "\uDB80\uDF42",
                                    if (submitting) "登录中.." else "登录",
                                    enabled = !submitting
                                ) {
                                    attemptLogin()
                                }
                                Space8w()
                                CircleIconButton(
                                    "\uEBCD",
                                    "注册",
                                    bgColor = MaterialTheme.colorScheme.tertiary
                                ) {
                                    showMsAccountDialog = true
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircleIconButton(
                                    "\uF084",
                                    "忘记密码"
                                ) {
                                    onOpenResetPassword?.invoke()
                                }
                            }
                            loginError?.let { message ->
                                LoginMessageDialog(
                                    title = "错误",
                                    message = message,
                                    confirmColor = MaterialTheme.colorScheme.error,
                                    onDismiss = { loginError = null }
                                )
                            }
                            Box(
                                modifier = Modifier.width(20.dp).height(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (submitting) {
                                    CircularProgressIndicator(modifier = Modifier.width(16.dp), strokeWidth = 2.dp)
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "v" + Const.VERSION_NUMBER + if (DEBUG) "debug" else "",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        // MS Account Dialog
        if (showMsAccountDialog) {
            Dialog(onDismissRequest = { showMsAccountDialog = false }) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "请选择注册方式",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    showMsAccountDialog = false
                                    onOpenRegister?.invoke(true)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("用微软MC正版号注册")
                            }
                            OutlinedButton(
                                onClick = {
                                    showMsAccountDialog = false
                                    onOpenRegister?.invoke(false)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("用QQ邮箱注册")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginMessageDialog(
    title: String,
    message: String,
    confirmColor: Color,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Left,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("明白", color = confirmColor)
            }
        },
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface
    )
}
