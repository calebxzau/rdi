package calebxzau.rdi.client.logic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

fun interface LoginGateway {
    suspend fun login(account: String, password: String): Result<Unit>
}

data class LoginState(
    val submitting: Boolean = false,
    val errorMessage: String? = null,
    val loggedIn: Boolean = false,
)

/** Shared screen logic; credentials are never retained in the observable state. */
class LoginController(private val gateway: LoginGateway) {
    private val mutableState = MutableStateFlow(LoginState())
    val state = mutableState.asStateFlow()
    private val submission = Mutex()

    suspend fun login(account: String, password: String): Result<Unit> {
        if (!submission.tryLock()) return Result.failure(IllegalStateException("正在登录，请稍候"))
        try {
            if (account.isBlank() || password.isBlank()) {
                mutableState.value = LoginState(errorMessage = "未填写完整")
                return Result.failure(IllegalArgumentException("未填写完整"))
            }
            mutableState.value = LoginState(submitting = true)
            val result = try {
                withContext(Dispatchers.IO) { gateway.login(account, password) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Result.failure(error)
            }
            result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            mutableState.value = result.fold(
                onSuccess = { LoginState(loggedIn = true) },
                onFailure = { LoginState(errorMessage = it.message ?: "登录失败") },
            )
            return result
        } finally {
            if (mutableState.value.submitting) mutableState.value = LoginState()
            submission.unlock()
        }
    }
}
