package calebxzau.rdi.client.logic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginControllerTest {
    @Test
    fun validatesBeforeCallingGateway(): Unit = runBlocking {
        var called = false
        val controller = LoginController { _, _ -> called = true; Result.success(Unit) }
        assertTrue(controller.login(" ", "password").isFailure)
        assertFalse(called)
        assertEquals("未填写完整", controller.state.value.errorMessage)
    }

    @Test
    fun reportsFailureAndAllowsRetry(): Unit = runBlocking {
        var fail = true
        val controller = LoginController { _, _ ->
            if (fail) Result.failure(IllegalStateException("账号错误")) else Result.success(Unit)
        }
        assertTrue(controller.login("example", "example").isFailure)
        assertEquals("账号错误", controller.state.value.errorMessage)
        fail = false
        controller.login("example", "example").getOrThrow()
        assertTrue(controller.state.value.loggedIn)
        assertFalse(controller.state.value.submitting)
    }

    @Test
    fun duplicateSubmissionDoesNotCallGatewayAgain(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val controller = LoginController { _, _ ->
            calls++
            entered.complete(Unit)
            release.await()
            Result.success(Unit)
        }
        val first = async { controller.login("example", "example") }
        entered.await()
        assertTrue(controller.login("example", "example").isFailure)
        release.complete(Unit)
        first.await().getOrThrow()
        assertEquals(1, calls)
    }
}
