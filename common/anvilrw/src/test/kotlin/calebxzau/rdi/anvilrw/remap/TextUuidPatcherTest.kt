package calebxzau.rdi.anvilrw.remap

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TextUuidPatcherTest {
    private val pcl = UUID.fromString("00000000-0000-300c-9be5-0017dec2993d")
    private val online = UUID.fromString("1512ef02-2b79-4494-a4e9-e35cd8e03923")
    private val target = UUID.fromString("00112233-4455-6677-8899-aabb00000000")
    private val patcher = TextUuidPatcher(mapOf(pcl to target))

    private fun dashless(uuid: UUID) = uuid.toString().replace("-", "")

    private fun ints(uuid: UUID): List<Int> = listOf(
        (uuid.mostSignificantBits ushr 32).toInt(),
        uuid.mostSignificantBits.toInt(),
        (uuid.leastSignificantBits ushr 32).toInt(),
        uuid.leastSignificantBits.toInt(),
    )

    private fun patch(text: String): TextPatchResult = patcher.patch(text.toByteArray(Charsets.UTF_8)).getOrThrow()

    @Test
    fun replacesDashedAndDashlessTextKeepingStyle() {
        val upper = pcl.toString().uppercase()
        val result = patch("﻿中文 ${pcl}\r\n\"${dashless(pcl)}\": 1\nkey=${upper} 😀\n")

        assertEquals(
            "﻿中文 ${target}\r\n\"${dashless(target)}\": 1\nkey=${target.toString().uppercase()} 😀\n",
            result.bytes.toString(Charsets.UTF_8),
        )
        assertEquals(mapOf(pcl to 3), result.replacements)
    }

    @Test
    fun ignoresPartialAndUnmappedUuids() {
        val text = "a${pcl} ${pcl}0 ${online} ${dashless(pcl).substring(1)} -${pcl}"
        val result = patch(text)

        assertFalse(result.changed)
        assertEquals(text, result.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun replacesIntArraysOnlyWhenAllFourIntsMatch() {
        val (a, b, c, d) = ints(pcl)
        val (ta, tb, tc, td) = ints(target)
        val text = "{Owner:[I; ${a}, ${b},${c} ,  ${d}], json:[${a},${b},${c},${d}], other:[I;${a},${b},${c},0], long:[I;1,2,3,99999999999]}"

        val result = patch(text)

        assertEquals(
            "{Owner:[I; ${ta}, ${tb},${tc} ,  ${td}], json:[${ta},${tb},${tc},${td}], other:[I;${a},${b},${c},0], long:[I;1,2,3,99999999999]}",
            result.bytes.toString(Charsets.UTF_8),
        )
        assertEquals(mapOf(pcl to 2), result.replacements)
    }

    @Test
    fun mostLeastPairsAreOnlyDetected() {
        val text = "{OwnerMost: ${pcl.mostSignificantBits}L, OwnerLeast: ${pcl.leastSignificantBits}L, \"xMost\": ${pcl.mostSignificantBits}, \"xLeast\": ${pcl.leastSignificantBits}, yMost: 1L, yLeast: 2L}"

        val result = patch(text)

        assertFalse(result.changed)
        assertEquals(2, result.mostLeastHits)
    }

    @Test
    fun swapsTwoProfiles() {
        val swap = TextUuidPatcher(mapOf(pcl to online, online to pcl))
        val result = swap.patch("${pcl} ${online} ${ints(online).joinToString(",", "[I;", "]")}".toByteArray()).getOrThrow()

        assertEquals("${online} ${pcl} ${ints(pcl).joinToString(",", "[I;", "]")}", result.bytes.toString(Charsets.UTF_8))
    }

    @Test
    fun rejectsTextThatIsNotStrictUtf8() {
        val gbk = "玩家 ${pcl}".toByteArray(charset("GBK"))
        assertFalse(TextUuidPatcher.isStrictUtf8(gbk))
        assertIs<NotUtf8TextException>(patcher.patch(gbk).exceptionOrNull())
        assertIs<NotUtf8TextException>(patcher.patch("a\u0000b".toByteArray()).exceptionOrNull())
        assertTrue(TextUuidPatcher.isStrictUtf8("plain ascii".toByteArray()))
        assertContentEquals(ByteArray(0), patcher.patch(ByteArray(0)).getOrThrow().bytes)
    }
}
