package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.common.model.RAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostCreateFriendsTest {
    private val owner = ObjectId("66a000000000000000000004")
    private val friend = RAccount.Dto(ObjectId("66a000000000000000000005"), "好友", RAccount.Cloth())

    @Test
    fun `one add action enables submission and removing last friend blocks creation`() = runBlocking {
        val gateway = FakeGateway(friend)
        val vm = loaded(gateway)
        assertFalse(vm.uiState.value.canSubmit)
        vm.submit()
        assertEquals("至少邀请1位好友一起玩", vm.uiState.value.statusMessage)
        assertTrue(gateway.submissions.isEmpty())

        lookup(vm)
        assertEquals(listOf(friend), vm.uiState.value.initialMembers)
        assertFalse(vm.uiState.value.friendDialogOpen)
        assertTrue(vm.uiState.value.canSubmit)
        vm.removeFriend(friend.id)
        assertFalse(vm.uiState.value.canSubmit)
        vm.submit()
        assertTrue(gateway.submissions.isEmpty())

        lookup(vm)
        vm.submit()
        withTimeout(5_000) { assertIs<HostCreateEvent.LegacyCreateSubmitted>(vm.events.first()) }
        val dto = assertIs<HostCreateSubmission.CreateLegacy>(gateway.submissions.single()).dto
        assertEquals(listOf(friend.id), dto.initialMemberIds)
    }

    @Test
    fun `own account and duplicate friend cannot be added`() = runBlocking {
        val gateway = FakeGateway(friend.copy(id = owner))
        val vm = loaded(gateway)
        lookup(vm)
        assertTrue(vm.uiState.value.friendDialogOpen)
        assertTrue(vm.uiState.value.friendLookupError!!.contains("自己"))
        assertTrue(vm.uiState.value.initialMembers.isEmpty())

        gateway.lookupResult = Result.success(friend)
        lookup(vm)
        lookup(vm)
        assertTrue(vm.uiState.value.friendDialogOpen)
        assertEquals("已添加这位好友", vm.uiState.value.friendLookupError)
        assertEquals(listOf(friend), vm.uiState.value.initialMembers)
    }

    @Test
    fun `lookup errors are visible and can be retried`() = runBlocking {
        val gateway = FakeGateway(friend)
        val vm = loaded(gateway)
        gateway.lookupResult = Result.failure(IllegalStateException("无此账号"))
        lookup(vm)
        assertEquals("无此账号", vm.uiState.value.friendLookupError)
        assertTrue(vm.uiState.value.friendDialogOpen)
        assertFalse(vm.uiState.value.canSubmit)
        gateway.lookupResult = Result.success(friend)
        lookup(vm)
        assertNull(vm.uiState.value.friendLookupError)
        assertEquals(listOf(friend), vm.uiState.value.initialMembers)
        assertFalse(vm.uiState.value.friendDialogOpen)
    }

    @Test
    fun `session changes clear selected friends and close dialog`() = runBlocking {
        val vm = loaded(FakeGateway(friend))
        lookup(vm)
        assertEquals(listOf(friend), vm.uiState.value.initialMembers)
        vm.openFriendDialog()
        vm.updateFriendQq("87654321")
        vm.updateSessionIdentity("other-account:server", ObjectId())
        assertTrue(vm.uiState.value.initialMembers.isEmpty())
        assertFalse(vm.uiState.value.friendDialogOpen)
        assertEquals("", vm.uiState.value.friendQq)
        assertFalse(vm.uiState.value.canSubmit)
    }

    @Test
    fun `pending lookup cannot add a friend after session switch`() = runBlocking {
        val gateway = FakeGateway(friend)
        val vm = loaded(gateway)
        val gate = CompletableDeferred<Result<RAccount.Dto>>()
        gateway.lookupGate = gate
        vm.openFriendDialog()
        vm.updateFriendQq("12345678")
        vm.addFriend()
        withTimeout(5_000) { gateway.lookupStarted.await() }
        vm.submit()
        assertTrue(gateway.submissions.isEmpty())
        vm.updateSessionIdentity("other-account:server", ObjectId())
        gate.complete(Result.success(friend))
        withTimeout(5_000) { gateway.lookupFinished.await() }
        assertFalse(vm.uiState.value.friendDialogOpen)
        assertFalse(vm.uiState.value.friendLookupLoading)
        assertTrue(vm.uiState.value.initialMembers.isEmpty())
    }

    @Test
    fun `canceling the dialog cancels a pending add and reopening clears input`() = runBlocking {
        val gateway = FakeGateway(friend)
        val vm = loaded(gateway)
        val gate = CompletableDeferred<Result<RAccount.Dto>>()
        gateway.lookupGate = gate
        vm.openFriendDialog()
        vm.updateFriendQq("12345678")
        vm.addFriend()
        withTimeout(5_000) { gateway.lookupStarted.await() }
        vm.dismissFriendDialog()
        gate.complete(Result.success(friend))
        withTimeout(5_000) { gateway.lookupFinished.await() }
        assertFalse(vm.uiState.value.friendDialogOpen)
        assertFalse(vm.uiState.value.friendLookupLoading)
        assertTrue(vm.uiState.value.initialMembers.isEmpty())
        vm.openFriendDialog()
        assertEquals("", vm.uiState.value.friendQq)
        assertNull(vm.uiState.value.friendLookupError)
    }

    private suspend fun loaded(gateway: FakeGateway): HostCreateViewModel {
        val vm = HostCreateViewModel(
            HostCreateViewModelArgs(
                hostId = null, defaultHostName = "测试房间",
                sourceId = "66a000000000000000000002", legacyVersionName = "1.0",
            ),
            gateway,
        )
        withTimeout(5_000) { vm.uiState.first { !it.loading } }
        vm.updateSessionIdentity("account:server", owner)
        return vm
    }

    private suspend fun lookup(vm: HostCreateViewModel) {
        if (!vm.uiState.value.friendDialogOpen) vm.openFriendDialog()
        vm.updateFriendQq(" 12345678 ")
        vm.addFriend()
        withTimeout(5_000) { vm.uiState.first { !it.friendLookupLoading } }
    }

    private class FakeGateway(friend: RAccount.Dto) : HostCreateGateway {
        var lookupResult: Result<RAccount.Dto> = Result.success(friend)
        var lookupGate: CompletableDeferred<Result<RAccount.Dto>>? = null
        val lookupStarted = CompletableDeferred<Unit>()
        val lookupFinished = CompletableDeferred<Unit>()
        val submissions = mutableListOf<HostCreateSubmission>()

        override suspend fun loadInitial(hostId: ObjectId?) = Result.success(HostCreateInitialData())

        override suspend fun findFriend(qq: String): Result<RAccount.Dto> {
            assertEquals("12345678", qq)
            lookupStarted.complete(Unit)
            return try {
                lookupGate?.await() ?: lookupResult
            } finally {
                lookupFinished.complete(Unit)
            }
        }

        override suspend fun createOrUpdate(submission: HostCreateSubmission): Result<Unit> {
            submissions += submission
            return Result.success(Unit)
        }

        override suspend fun requestHostPackUpdate(hostId: ObjectId): Result<Unit> = Result.success(Unit)
    }
}
