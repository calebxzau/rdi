package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.common.exception.RequestError
import calebxzhou.rdi.common.model.DmHostBrief
import calebxzhou.rdi.common.model.DmHostState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmRoomListViewModelTest {
    private val room = DmHostBrief("01a17728-b371-7013-9be0-4ac4742f0818", "测试房间", DmHostState.Offline)
    private val otherRoom = DmHostBrief("01a17728-b371-7013-9be0-4ac4742f0819", "另一个房间", DmHostState.Online)

    @Test
    fun `nothing is requested until the rooms are shown`() {
        val gateway = FakeDmRoomGateway(listOf(room))
        val viewModel = DmRoomListViewModel(gateway)

        viewModel.onMenuResumed()

        assertFalse(viewModel.uiState.value.shown)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun `toggling shows the rooms and toggling again hides them`() = runBlocking {
        val gateway = FakeDmRoomGateway(listOf(room))
        val viewModel = DmRoomListViewModel(gateway)

        viewModel.toggle()
        awaitState(viewModel) { it.rooms == listOf(room) && !it.loading }
        assertTrue(viewModel.uiState.value.shown)

        viewModel.toggle()
        val hidden = viewModel.uiState.value
        assertFalse(hidden.shown)
        assertTrue(hidden.rooms.isEmpty())
        assertEquals(listOf("list"), gateway.calls)
    }

    @Test
    fun `returning to the menu reloads shown rooms`() = runBlocking {
        val gateway = FakeDmRoomGateway(listOf(room))
        val viewModel = DmRoomListViewModel(gateway)
        viewModel.toggle()
        awaitState(viewModel) { it.rooms == listOf(room) && !it.loading }

        gateway.rooms = listOf(room, otherRoom)
        viewModel.onMenuResumed()

        awaitState(viewModel) { it.rooms == listOf(room, otherRoom) && !it.loading }
        assertEquals(listOf("list", "list"), gateway.calls)
    }

    @Test
    fun `a load failure is reported and the toggle stays on`() = runBlocking {
        val gateway = FakeDmRoomGateway(listOf(room), listFailure = RequestError("服务不可用"))
        val viewModel = DmRoomListViewModel(gateway)

        viewModel.toggle()

        val state = awaitState(viewModel) { it.errorMessage != null && !it.loading }
        assertEquals("加载测试房间失败：服务不可用", state.errorMessage)
        assertTrue(state.shown)
        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `a deleted room leaves the list`() = runBlocking {
        val gateway = FakeDmRoomGateway(listOf(room, otherRoom))
        val viewModel = DmRoomListViewModel(gateway)
        viewModel.toggle()
        awaitState(viewModel) { it.rooms.size == 2 && !it.loading }

        viewModel.delete(room.id)

        val state = awaitState(viewModel) { it.rooms == listOf(otherRoom) && !it.loading }
        assertEquals("房间已删除", state.message)
        assertEquals(listOf("list", "delete:${room.id}"), gateway.calls)
    }

    @Test
    fun `a refused deletion keeps the room and shows the reason`() = runBlocking {
        val gateway = FakeDmRoomGateway(
            listOf(room),
            deleteFailure = RequestError("房间还在运行，房主退出后才能删除"),
        )
        val viewModel = DmRoomListViewModel(gateway)
        viewModel.toggle()
        awaitState(viewModel) { it.rooms == listOf(room) && !it.loading }

        viewModel.delete(room.id)

        val state = awaitState(viewModel) { it.errorMessage != null && !it.loading }
        assertEquals("删除房间失败：房间还在运行，房主退出后才能删除", state.errorMessage)
        assertEquals(listOf(room), state.rooms)
    }

    @Test
    fun `hiding during a load discards its result`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val gateway = FakeDmRoomGateway(listOf(room), listGate = release)
        val viewModel = DmRoomListViewModel(gateway)

        viewModel.toggle()
        awaitState(viewModel) { it.loading }
        viewModel.toggle()
        release.complete(Unit)

        val state = viewModel.uiState.value
        assertFalse(state.shown)
        assertFalse(state.loading)
        assertTrue(state.rooms.isEmpty())
    }

    private suspend fun awaitState(
        viewModel: DmRoomListViewModel,
        predicate: (DmRoomListUiState) -> Boolean,
    ): DmRoomListUiState = withTimeout(5_000) { viewModel.uiState.filter(predicate).first() }

    private class FakeDmRoomGateway(
        var rooms: List<DmHostBrief>,
        private val listFailure: Throwable? = null,
        private val deleteFailure: Throwable? = null,
        private val listGate: CompletableDeferred<Unit>? = null,
    ) : DmRoomGateway {
        val calls = CopyOnWriteArrayList<String>()

        override suspend fun listMine(): Result<List<DmHostBrief>> {
            calls += "list"
            listGate?.await()
            return listFailure?.let { Result.failure(it) } ?: Result.success(rooms)
        }

        override suspend fun delete(id: String): Result<Unit> {
            calls += "delete:$id"
            return deleteFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }
    }
}
