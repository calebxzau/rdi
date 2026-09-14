package calebxzau.rdi.quests.ftb.snbt

import kotlinx.serialization.json.Json
import net.benwoodworth.knbt.NbtByte
import net.benwoodworth.knbt.NbtByteArray
import net.benwoodworth.knbt.NbtDouble
import net.benwoodworth.knbt.NbtFloat
import net.benwoodworth.knbt.NbtInt
import net.benwoodworth.knbt.NbtIntArray
import net.benwoodworth.knbt.NbtLong
import net.benwoodworth.knbt.NbtLongArray
import net.benwoodworth.knbt.NbtShort
import net.benwoodworth.knbt.NbtString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FtbSnbtTest {
    @Test
    fun readsFtbCommentsCommaFreeCompoundsAndQuotedContent(): Unit {
        val result = FtbSnbt.parse(
            """
            # file comment
            {
              title: "Quest, #1"
              url: 'https://example.test/a?x=1,y=2#section'
              escaped: "line\nnext\t\"quoted\\path"
              nested: {
                component: {id: 'mod:component', value: "a\\\\b"}
              }
            }
            // trailing whole-line comment
            """.trimIndent()
        ).getOrThrow()

        assertEquals("Quest, #1", (result["title"] as NbtString).value)
        assertEquals("https://example.test/a?x=1,y=2#section", (result["url"] as NbtString).value)
        assertEquals("line\nnext\t\"quoted\\path", (result["escaped"] as NbtString).value)
        assertEquals("mod:component", ((result["nested"] as net.benwoodworth.knbt.NbtCompound)["component"] as net.benwoodworth.knbt.NbtCompound)["id"].let { (it as NbtString).value })
    }

    @Test
    fun preservesNumericWidthsLongPrecisionAndArrays(): Unit {
        val result = FtbSnbt.parse(
            "{byte:1b, short:-2s, int:3, long:9007199254740993L, float:1.25f, double:2.5d, " +
                "bytes:[B;1b,0b,-1b], ints:[I;1,2,-3], longs:[L;9007199254740993L], " +
                "lowerBytes:[b;1,0], emptyBytes:[B;], empty:[]}"
        ).getOrThrow()

        assertIs<NbtByte>(result["byte"])
        assertIs<NbtShort>(result["short"])
        assertIs<NbtInt>(result["int"])
        assertEquals(9007199254740993L, (result["long"] as NbtLong).value)
        assertIs<NbtFloat>(result["float"])
        assertIs<NbtDouble>(result["double"])
        assertEquals(NbtByteArray(byteArrayOf(1, 0, -1)), result["bytes"])
        assertEquals(NbtIntArray(intArrayOf(1, 2, -3)), result["ints"])
        assertEquals(NbtLongArray(longArrayOf(9007199254740993L)), result["longs"])
        assertEquals(NbtByteArray(byteArrayOf(1, 0)), result["lowerBytes"])
        assertEquals(NbtByteArray(byteArrayOf()), result["emptyBytes"])
        assertTrue((result["empty"] as net.benwoodworth.knbt.NbtList<*>).isEmpty())
    }

    @Test
    fun jsonWrapperUsesSingleCanonicalSnbtStringAndRoundTripsAllTypes(): Unit {
        val source = FtbSnbt.parse(
            "{b:1b,s:2s,i:3,l:9007199254740993L,f:1.5f,d:2.25d,txt:'hello, world', " +
                "'key:with,\\\\slash':\"quote\\\" and path\", actual:\"line\\nnext\", " +
                "literal:\"literal\\\\n\", path:\"C:\\\\Users\\\\test\\\\\", " +
                "positive:Infinityf, nan:NaNf, doubleNan:NaNd, " +
                "ba:[B;1b,2b],ia:[I;3,4],la:[L;5L,6L],list:[{k:'v'}], nested:{x:false}}"
        ).getOrThrow()
        val json = Json.encodeToString(FtbSnbtDataSerializer, FtbSnbtData(source))
        assertTrue(json.startsWith("\"{") && json.endsWith("}\""))
        val decoded = Json.decodeFromString(FtbSnbtDataSerializer, json)
        assertEquals(source, decoded.nbt)
        assertEquals("line\nnext", (decoded.nbt["actual"] as NbtString).value)
        assertEquals("literal\\n", (decoded.nbt["literal"] as NbtString).value)
        assertEquals("C:\\Users\\test\\", (decoded.nbt["path"] as NbtString).value)
        assertEquals("quote\" and path", (decoded.nbt["key:with,\\slash"] as NbtString).value)
        assertEquals(Float.POSITIVE_INFINITY, (decoded.nbt["positive"] as NbtFloat).value)
        assertTrue((decoded.nbt["nan"] as NbtFloat).value.isNaN())
        assertTrue((decoded.nbt["doubleNan"] as NbtDouble).value.isNaN())
    }

    @Test
    fun reportsMalformedTrailingDuplicateAndInvalidValuesWithLocation(): Unit {
        val malformed = FtbSnbt.parse("{\n  value: [1,\n}").exceptionOrNull()
        assertTrue(malformed?.message?.contains(":") == true)
        assertTrue(FtbSnbt.parse("{a:1} trailing").isFailure)
        assertTrue(FtbSnbt.parse("{a:1,a:2}").isFailure)
        assertTrue(FtbSnbt.parse("{a:null}").isFailure)
        assertTrue(FtbSnbt.parse("{a:[1,'two']}").isFailure)
        assertTrue(FtbSnbt.parse("{a:[B;128]}").isFailure)
        assertTrue(FtbSnbt.parse("{a:128b}").isFailure)
        assertTrue(FtbSnbt.parse("{a:[X;1]}").isFailure)
        assertTrue(FtbSnbt.parse("{a:foo:b:2}").isFailure)
        assertTrue(FtbSnbt.parse("{a:[1{b:2}]}").isFailure)
    }

    @Test
    fun supportsFtbUnquotedWordsEqualsScientificAndSpecialFloats(): Unit {
        val result = FtbSnbt.parse(
            "{translation.key:值, assigned = 1e3, positive:Infinity, negative:-Infinityd, " +
                "floatPositive:Infinityf, nan:NaN, floatNan:NaNf}"
        ).getOrThrow()

        assertEquals("值", (result["translation.key"] as NbtString).value)
        assertEquals(1000.0, (result["assigned"] as NbtDouble).value)
        assertEquals(Double.POSITIVE_INFINITY, (result["positive"] as NbtDouble).value)
        assertEquals(Double.NEGATIVE_INFINITY, (result["negative"] as NbtDouble).value)
        assertEquals(Float.POSITIVE_INFINITY, (result["floatPositive"] as NbtFloat).value)
        assertTrue((result["nan"] as NbtDouble).value.isNaN())
        assertTrue((result["floatNan"] as NbtFloat).value.isNaN())
    }

    @Test
    fun rejectsExcessiveNesting(): Unit {
        val nested = "{a:".repeat(260) + "1" + "}".repeat(260)
        assertTrue(FtbSnbt.parse(nested).isFailure)

        val valid = "{a:".repeat(255) + "1" + "}".repeat(255)
        assertTrue(FtbSnbt.parse(valid).isSuccess)
    }
}
