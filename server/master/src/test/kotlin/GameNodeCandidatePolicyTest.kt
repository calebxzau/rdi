import calebxzhou.rdi.common.model.GameNodeEntry
import calebxzhou.rdi.common.model.ServerEntry
import calebxzhou.rdi.common.serdesJson
import calebxzhou.rdi.master.AppConfig
import calebxzhou.rdi.master.GameNodeRuleConfig
import calebxzhou.rdi.master.service.GameNodeCandidatePolicy
import kotlinx.serialization.Serializable
import net.peanuuutz.tomlkt.Toml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val NODE_TOML = "[[gameNode.nodes]]\nid = 0\nname = \"主节点\"\ngameAddr = \"203.0.113.2:65230\"\n"

class GameNodeCandidatePolicyTest {
    private val main = node(0, "主节点")
    private val backup = node(1, "备用", gameBackup = true)
    private val shanghai = node(2, "上海", provinces = listOf("上海"))
    private val peak = node(3, "高峰", peekHourOnly = true)
    private val international = node(4, "国际出口", matchOutsideChina = true)
    private val all = listOf(main, backup, shanghai, peak, international)

    private fun select(selected: GameNodeRuleConfig, nodes: List<GameNodeRuleConfig> = all) =
        GameNodeCandidatePolicy.select(nodes, selected).map { it.id }

    @Test
    fun `player on the main node only reconnects to it`() {
        assertEquals(listOf(0), select(main))
    }

    @Test
    fun `player on another node falls back to the main node only`() {
        assertEquals(listOf(2, 0), select(shanghai))
        assertEquals(listOf(1, 0), select(backup))
        assertEquals(listOf(3, 0), select(peak))
        assertEquals(listOf(4, 0), select(international))
    }

    @Test
    fun `node sharing the main address appears once`() {
        val sameAddress = node(5, "同地址", gameAddr = main.gameAddr)
        assertEquals(listOf(5), select(sameAddress, listOf(main, sameAddress)))
    }

    @Test
    fun `selected node remains when no main node is configured`() {
        assertEquals(listOf(2), select(shanghai, listOf(shanghai)))
    }

    @Test
    fun `resume is on when config omits the switch`() {
        val config = Toml.decodeFromString(AppConfig.serializer(), NODE_TOML)
        assertEquals(1, config.gameNode.nodes.size)
        assertTrue(config.gameNode.sessionResume)
    }

    @Test
    fun `config can turn resume off`() {
        val config = Toml.decodeFromString(AppConfig.serializer(), "[gameNode]\nsessionResume = false\n\n$NODE_TOML")
        assertEquals(1, config.gameNode.nodes.size)
        assertFalse(config.gameNode.sessionResume)
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
