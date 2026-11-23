package calebxzau.rdi.client.ui.viewmodel

import calebxzhou.rdi.common.model.AllGameRules
import calebxzhou.rdi.common.model.Host
import calebxzhou.rdi.common.serdesJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.bson.types.ObjectId
import java.io.File
import java.text.Collator
import java.util.Locale
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U4 batch c1: the Slint launcher reads the game rule table and checks its room request bodies against
 * `slint-proto/protocol/fixtures/game_rules.json` and `room_requests.json`. Run with `RDI_WRITE_FIXTURES=1`
 * to rewrite them; otherwise the test checks they are current.
 */
class HostRequestFixtureTest {
    private val fixtures: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { it.resolve("slint-proto/protocol/fixtures") }
        .first { it.isDirectory }
    private val pretty = Json { prettyPrint = true }

    private fun check(name: String, element: JsonElement) {
        val text = pretty.encodeToString(JsonElement.serializer(), element) + "\n"
        val file = fixtures.resolve(name)
        if (System.getenv("RDI_WRITE_FIXTURES") == "1") file.writeText(text)
        assertTrue(file.isFile, "缺少${name}，请用RDI_WRITE_FIXTURES=1运行本测试")
        assertEquals(file.readText().replace("\r\n", "\n"), text)
    }

    /** The display order of `GameRuleModal`: categories by Chinese collation, rules by name. */
    @Test
    fun `game rule table in display order`() {
        val collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)
        val grouped = AllGameRules.groupBy { it.category }.toSortedMap(compareBy(collator) { it })
        val table = buildJsonObject {
            putJsonArray("categories") {
                grouped.forEach { (category, rules) ->
                    addJsonObject {
                        put("name", category)
                        putJsonArray("rules") {
                            rules.sortedBy { it.name }.forEach { rule ->
                                addJsonObject {
                                    put("id", rule.id)
                                    put("name", rule.name)
                                    put("description", rule.description)
                                    put("valueType", rule.valueType.name)
                                    put("value", rule.value)
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(60, AllGameRules.size)
        check("game_rules.json", table)
    }

    @Test
    fun `room request bodies`() {
        val pack = ObjectId("66a000000000000000000002")
        val friends = listOf(ObjectId("66a000000000000000000010"), ObjectId("66a000000000000000000011"))
        val cases = listOf(
            "create-template" to serdesJson.encodeToJsonElement(
                Host.CreateDto.serializer(),
                Host.CreateDto(
                    name = "玩家的测试整合", modpackId = pack, packVer = "2.0.0", difficulty = 3, gameMode = 0,
                    levelType = "minecraft:flat", allowCheats = false, whitelist = true,
                    gameRules = mutableMapOf("keepInventory" to "true"),
                    baseWorldId = UUID.fromString("0193d3a4-1111-7000-8000-000000000001"),
                    initialMemberIds = friends,
                ),
            ),
            "create-generate" to serdesJson.encodeToJsonElement(
                Host.CreateDto.serializer(),
                Host.CreateDto(
                    name = "房间", modpackId = pack, packVer = "1.0", difficulty = 1, gameMode = 1,
                    levelType = "skyblockbuilder:skyblock", allowCheats = true, whitelist = false,
                    gameRules = mutableMapOf(), initialMemberIds = friends.take(1),
                ),
            ),
            "options-unchanged-terrain" to serdesJson.encodeToJsonElement(
                Host.OptionsDto.serializer(),
                Host.OptionsDto(
                    name = "修改后的名称", difficulty = 2, gameMode = 0, levelType = null,
                    allowCheats = false, whitelist = true, gameRules = mapOf("randomTickSpeed" to "10"),
                ),
            ),
            "options-terrain" to serdesJson.encodeToJsonElement(
                Host.OptionsDto.serializer(),
                Host.OptionsDto(
                    name = "房间", difficulty = 3, gameMode = 0, levelType = "minecraft:flat",
                    allowCheats = false, whitelist = true, gameRules = emptyMap(),
                ),
            ),
            "delete-keep-world" to serdesJson.encodeToJsonElement(Host.DeleteDto.serializer(), Host.DeleteDto(false)),
            "delete-world" to serdesJson.encodeToJsonElement(Host.DeleteDto.serializer(), Host.DeleteDto(true)),
        )
        check("room_requests.json", buildJsonObject {
            putJsonArray("cases") {
                cases.forEach { (name, json) ->
                    addJsonObject {
                        put("name", name)
                        put("json", json)
                    }
                }
            }
        })
    }
}
