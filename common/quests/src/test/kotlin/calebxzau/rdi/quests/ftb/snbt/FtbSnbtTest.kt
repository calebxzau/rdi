package calebxzau.rdi.quests.ftb.snbt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.SerializationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun jsonUsesNavigableNaturalPreviewValues(): Unit {
        val source = FtbSnbt.parse(
            "{b:1b,zero:false,s:2s,i:3,l:9007199254740993L,safeMin:-9007199254740991L, " +
                "safeMax:9007199254740991L,longMin:-9223372036854775808L,longMax:9223372036854775807L, " +
                "f:1.5f,d:2.25d,txt:'hello, world', textSpecial:'NaN', " +
                "'key:with,\\\\slash':\"quote\\\" and path\", actual:\"line\\nnext\", " +
                "literal:\"literal\\\\n\", path:\"C:\\\\Users\\\\test\\\\\", " +
                "positive:Infinityf, nan:NaNf, doubleNan:NaNd, " +
                "negative:-Infinityd, ba:[B;1b,0b,2b],ia:[I;3,4],la:[L;5L,6L], " +
                "emptyBytes:[B;],emptyInts:[I;],emptyLongs:[L;],empty:[],list:[{k:'v'}], " +
                "nested:{x:false}}"
        ).getOrThrow()
        val json = Json.encodeToString(FtbSnbtDataSerializer, FtbSnbtData(source))
        val jsonObject = Json.parseToJsonElement(json).jsonObject
        assertFalse(jsonObject.getValue("b").jsonPrimitive.isString)
        assertEquals("1", jsonObject.getValue("b").jsonPrimitive.content)
        assertEquals("0", jsonObject.getValue("zero").jsonPrimitive.content)
        assertEquals("2", jsonObject.getValue("s").jsonPrimitive.content)
        assertEquals("3", jsonObject.getValue("i").jsonPrimitive.content)
        assertFalse(jsonObject.getValue("safeMin").jsonPrimitive.isString)
        assertFalse(jsonObject.getValue("safeMax").jsonPrimitive.isString)
        assertTrue(jsonObject.getValue("l").jsonPrimitive.isString)
        assertTrue(jsonObject.getValue("longMin").jsonPrimitive.isString)
        assertTrue(jsonObject.getValue("longMax").jsonPrimitive.isString)
        assertEquals("9007199254740993", jsonObject.getValue("l").jsonPrimitive.content)
        assertEquals("hello, world", jsonObject.getValue("txt").jsonPrimitive.content)
        assertEquals("NaN", jsonObject.getValue("textSpecial").jsonPrimitive.content)
        assertFalse(jsonObject.getValue("f").jsonPrimitive.isString)
        assertFalse(jsonObject.getValue("d").jsonPrimitive.isString)
        assertEquals("Infinity", jsonObject.getValue("positive").jsonPrimitive.content)
        assertEquals("NaN", jsonObject.getValue("nan").jsonPrimitive.content)
        assertEquals("-Infinity", jsonObject.getValue("negative").jsonPrimitive.content)
        assertEquals(listOf("1", "0", "2"), jsonObject.getValue("ba").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("3", "4"), jsonObject.getValue("ia").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("5", "6"), jsonObject.getValue("la").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(jsonObject.getValue("emptyBytes").jsonArray.isEmpty())
        assertTrue(jsonObject.getValue("emptyInts").jsonArray.isEmpty())
        assertTrue(jsonObject.getValue("emptyLongs").jsonArray.isEmpty())
        assertTrue(jsonObject.getValue("empty").jsonArray.isEmpty())
        assertEquals("v", jsonObject.getValue("list").jsonArray.single().jsonObject.getValue("k").jsonPrimitive.content)
    }

    @Test
    fun rejectsPreviewJsonDeserializationExplicitly(): Unit {
        assertFailsWith<SerializationException> {
            Json.decodeFromString<FtbSnbtData>("{}")
        }
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
