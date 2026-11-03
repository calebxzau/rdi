import calebxzhou.rdi.common.model.GameNodeEntry
import calebxzhou.rdi.common.model.ServerEntry
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.master.GameNodeConfig
import calebxzhou.rdi.master.GameNodeRuleConfig
import calebxzhou.rdi.master.service.GameNodeCandidatePolicy
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameNodeCandidatePolicyTest {
    private val main = node(0, "主节点")
    private val backup = node(1, "备用", gameBackup = true)
    private val shanghai = node(2, "上海", provinces = listOf("上海"))
    private val peak = node(3, "高峰", peekHourOnly = true)
    private val international = node(4, "国际出口", matchOutsideChina = true)
    private val all = listOf(main, backup, shanghai, peak, international)

    private fun select(
        selected: GameNodeRuleConfig,
        nodes: List<GameNodeRuleConfig> = all,
        peakHour: Boolean = true,
        outsideMainland: Boolean = false,
    ) = GameNodeCandidatePolicy.select(nodes, selected, peakHour, outsideMainland).map { it.id }

    @Test
    fun `selected node comes first, then backup, main and the rest`() {
        assertEquals(listOf(2, 1, 0, 3), select(shanghai))
    }

    @Test
    fun `manually selected backup stays first without repeating`() {
        assertEquals(listOf(1, 0, 2, 3), select(backup))
    }

    @Test
    fun `off-peak hides unselected peak nodes`() {
        assertEquals(listOf(2, 1, 0), select(shanghai, peakHour = false))
    }

    @Test
    fun `selected peak node is kept off-peak`() {
        assertEquals(listOf(3, 1, 0, 2), select(peak, peakHour = false))
    }

    @Test
    fun `players in mainland China do not get the international node`() {
        assertFalse(4 in select(shanghai))
    }

    @Test
    fun `players outside the mainland keep the international node`() {
        assertEquals(listOf(4, 1, 0, 2, 3), select(international, outsideMainland = true))
        assertTrue(4 in select(main, outsideMainland = true))
    }

    @Test
    fun `manually selected international node stays for players in China`() {
        assertEquals(listOf(4, 1, 0, 2, 3), select(international))
    }

    @Test
    fun `main node is always included even if another rule would hide it`() {
        val hiddenMain = node(0, "主节点", peekHourOnly = true, matchOutsideChina = true)
        assertEquals(listOf(2, 0), select(shanghai, listOf(hiddenMain, shanghai), peakHour = false))
    }

    @Test
    fun `single configured node gives a single candidate`() {
        assertEquals(listOf(0), select(main, listOf(main)))
    }

    @Test
    fun `nodes sharing an address appear once`() {
        val sameAddress = node(5, "同地址", gameAddr = shanghai.gameAddr)
        assertEquals(listOf(2, 0), select(shanghai, listOf(main, shanghai, sameAddress)))
    }

    @Test
    fun `resume switch is off when old config omits it`() {
        val config = Toml.decodeFromString(GameNodeConfig.serializer(), "nodes = []")
        assertFalse(config.sessionResume)
    }

    @Test
    fun `old launchers can read new entries`() {
        val new = serdesJson.encodeToString(
            ServerEntry.serializer(),
            ServerEntry(
                nodeName = "上海-上海电信",
                gameAddr = "203.0.113.1:65230",
                candidates = listOf(GameNodeEntry("主节点", "203.0.113.2:65230")),
                sessionResume = true,
            ),
        )
        assertEquals(
            OldServerEntry(nodeName = "上海-上海电信", gameAddr = "203.0.113.1:65230"),
            serdesJson.decodeFromString(OldServerEntry.serializer(), new),
        )
    }

    @Test
    fun `new launchers can read old entries`() {
        val old = serdesJson.encodeToString(
            OldServerEntry.serializer(),
            OldServerEntry(nodeName = "主节点", gameAddr = "203.0.113.2:65230"),
        )
        val entry = serdesJson.decodeFromString(ServerEntry.serializer(), old)
        assertEquals(emptyList(), entry.candidates)
        assertFalse(entry.sessionResume)
    }

    /** The response shape before resume fields were added. */
    @Serializable
    private data class OldServerEntry(
        val api: String? = null,
        val useBackupNode: Boolean = false,
        val nodeName: String,
        val gameAddr: String,
    )

    private fun node(
        id: Int,
        name: String,
        gameAddr: String = "203.0.113.${id + 1}:65230",
        provinces: List<String> = emptyList(),
        matchOutsideChina: Boolean = false,
        peekHourOnly: Boolean = false,
        gameBackup: Boolean = false,
    ) = GameNodeRuleConfig(
        id = id,
        name = name,
        gameAddr = gameAddr,
        provinces = provinces,
        matchOutsideChina = matchOutsideChina,
        peekHourOnly = peekHourOnly,
        gameBackup = gameBackup,
    )
}
