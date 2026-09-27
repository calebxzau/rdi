package calebxzau.rdi.mc.v20.client

import calebxzhou.rdi.mc.common.RGlobalPlayerList
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.concurrent.Volatile

object GlobalPlayerListState {
    class Session internal constructor(val owner: Any, val generation: Long)
    class Snapshot internal constructor(
        val playerList: RGlobalPlayerList,
        val rows: List<RTabRow>,
        val playerCount: Int
    )

    private val sequence = AtomicLong()
    private val lock = Any()
    @Volatile
    private var activeSession: Session? = null
    @Volatile
    private var currentSnapshot = createSnapshot(emptyPlayerList())

    @JvmStatic
    fun current(): RGlobalPlayerList = currentSnapshot.playerList

    @JvmStatic
    fun snapshot(): Snapshot = currentSnapshot

    @JvmStatic
    fun beginSession(owner: Any): Long {
        val session = synchronized(lock) {
            Session(owner, sequence.incrementAndGet()).also {
                activeSession = it
                currentSnapshot = createSnapshot(emptyPlayerList())
            }
        }
        TabLayoutCache.clear()
        RdiClothesResolver.resetSession()
        GlobalPlayerAvatarCache.resetSession()
        return session.generation
    }

    @JvmStatic
    fun generationFor(owner: Any): Long? = synchronized(lock) {
        activeSession?.takeIf { it.owner === owner }?.generation
    }

    @JvmStatic
    fun session(): Session? = activeSession

    @JvmStatic
    fun isCurrent(session: Session): Boolean = synchronized(lock) {
        activeSession?.let { it.owner === session.owner && it.generation == session.generation } == true
    }

    @JvmStatic
    fun hasActiveSession(): Boolean = activeSession != null

    @JvmStatic
    fun containsPlayer(playerId: UUID): Boolean = currentSnapshot.playerList.hosts().any { host ->
        host.players().any { playerIdToUuid(it.playerId()) == playerId }
    }

    @JvmStatic
    fun update(owner: Any, generation: Long, playerList: RGlobalPlayerList): Boolean {
        return synchronized(lock) {
            val active = activeSession
            if (active == null || active.owner !== owner || active.generation != generation) {
                false
            } else {
                currentSnapshot = createSnapshot(playerList)
                TabLayoutCache.clear()
                GlobalPlayerAvatarCache.retainPlayers(playerList)
                true
            }
        }
    }

    @JvmStatic
    fun endSession(owner: Any?): Boolean {
        val ended = synchronized(lock) {
            val active = activeSession
            if (active == null || (owner != null && active.owner !== owner)) {
                false
            } else {
                activeSession = null
                sequence.incrementAndGet()
                currentSnapshot = createSnapshot(emptyPlayerList())
                true
            }
        }
        if (ended) {
            TabLayoutCache.clear()
            RdiClothesResolver.resetSession()
            GlobalPlayerAvatarCache.resetSession()
        }
        return ended
    }

    private fun emptyPlayerList() = RGlobalPlayerList(0L, emptyList())

    private fun createSnapshot(playerList: RGlobalPlayerList): Snapshot {
        val tabRows = TabRowBuilder.buildSnapshot(playerList)
        return Snapshot(playerList, tabRows.rows, tabRows.playerCount)
    }
}
