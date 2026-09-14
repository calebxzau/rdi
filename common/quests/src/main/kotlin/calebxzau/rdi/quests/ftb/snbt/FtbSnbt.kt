package calebxzau.rdi.quests.ftb.snbt

import net.benwoodworth.knbt.NbtByte
import net.benwoodworth.knbt.NbtByteArray
import net.benwoodworth.knbt.NbtCompound
import net.benwoodworth.knbt.NbtDouble
import net.benwoodworth.knbt.NbtFloat
import net.benwoodworth.knbt.NbtInt
import net.benwoodworth.knbt.NbtIntArray
import net.benwoodworth.knbt.NbtList
import net.benwoodworth.knbt.NbtLong
import net.benwoodworth.knbt.NbtLongArray
import net.benwoodworth.knbt.NbtShort
import net.benwoodworth.knbt.NbtString
import net.benwoodworth.knbt.NbtTag
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.LinkedHashMap

private const val MAX_INPUT_CHARS: Int = 32 * 1024 * 1024
private const val MAX_NESTING_DEPTH: Int = 256
private val INTEGER_PATTERN: Regex = Regex("[+-]?\\d+")
private val DOUBLE_PATTERN: Regex = Regex("[+-]?(?:(?:\\d+\\.\\d*)|(?:\\.\\d+)|(?:\\d+))(?:[eE][+-]?\\d+)?")

/** Reads the SNBT dialect written by FTB Library without requiring Minecraft at runtime. */
public object FtbSnbt {
    /** Parses one complete compound value and rejects any non-comment content after it. */
    public fun parse(text: String): Result<NbtCompound> {
        if (text.length > MAX_INPUT_CHARS) {
            return Result.failure(FtbSnbtSyntaxException("Input is too large (maximum is $MAX_INPUT_CHARS characters) at 1:1"))
        }

        return try {
            val parser = Parser(text)
            val value = parser.parseValue(0)
            val compound = value as? NbtCompound
                ?: parser.error("The root value must be a compound")
            parser.skipSpaceAndComments()
            if (!parser.atEnd()) {
                parser.error("Unexpected trailing content")
            }
            Result.success(compound)
        } catch (exception: FtbSnbtSyntaxException) {
            Result.failure(exception)
        }
    }

    /** Thrown internally for malformed SNBT. Its message includes a one-based line and column. */
    public class FtbSnbtSyntaxException internal constructor(message: String) : IllegalArgumentException(message)

    private class Parser(private val text: String) {
        private var position: Int = 0

        fun atEnd(): Boolean = position >= text.length

        fun error(message: String): Nothing {
            throw FtbSnbtSyntaxException("$message at ${line()}:${column()}")
        }

        fun skipSpaceAndComments() {
            while (true) {
                while (!atEnd() && text[position].isWhitespace()) position++
                if (atEnd() || !atLineStart()) return

                if (text[position] == '#') {
                    skipComment()
                } else if (text[position] == '/' && position + 1 < text.length && text[position + 1] == '/') {
                    skipComment()
                } else {
                    return
                }
            }
        }

        private fun skipComment() {
            while (!atEnd() && text[position] != '\n') position++
        }

        private fun atLineStart(): Boolean {
            var index = position - 1
            while (index >= 0 && text[index] != '\n') {
                if (!text[index].isWhitespace()) return false
                index--
            }
            return true
        }

        fun parseValue(depth: Int): NbtTag {
            if (depth > MAX_NESTING_DEPTH) error("Nesting depth exceeds $MAX_NESTING_DEPTH")
            skipSpaceAndComments()
            if (atEnd()) error("Unexpected end of input")
            return when (text[position]) {
                '{' -> parseCompound(depth + 1)
                '[' -> parseCollection(depth + 1)
                '\'', '"' -> NbtString(parseQuoted(text[position]))
                else -> parseScalar(parseWord(isKey = false))
            }
        }

        private fun parseCompound(depth: Int): NbtCompound {
            position++
            val values = LinkedHashMap<String, NbtTag>()
            while (true) {
                skipSpaceAndComments()
                if (atEnd()) error("Unterminated compound")
                if (text[position] == '}') {
                    position++
                    return NbtCompound(values)
                }
                val key = if (text[position] == '\'' || text[position] == '"') {
                    parseQuoted(text[position])
                } else {
                    parseWord(isKey = true)
                }
                skipSpaceAndComments()
                if (atEnd() || (text[position] != ':' && text[position] != '=')) {
                    error("Expected ':' or '=' after compound key")
                }
                position++
                if (values.containsKey(key)) error("Duplicate compound key '$key'")
                values[key] = parseValue(depth)
                skipSpaceAndComments()
                if (!atEnd() && text[position] == ',') position++
            }
        }

        private fun parseCollection(depth: Int): NbtTag {
            position++
            skipSpaceAndComments()
            if (!atEnd() && text[position] != ']') {
                val typePosition = position
                val type = text[position]
                position++
                skipSpaceAndComments()
                if ((type == 'B' || type == 'b' || type == 'I' || type == 'i' || type == 'L' || type == 'l') &&
                    !atEnd() && text[position] == ';'
                ) {
                    position++
                    return parseTypedArray(type.lowercaseChar(), depth)
                }
                if (type.isLetter() && !atEnd() && text[position] == ';') {
                    error("Unknown typed array '$type'")
                }
                position = typePosition
            }

            val values = ArrayList<NbtTag>()
            while (true) {
                skipSpaceAndComments()
                if (atEnd()) error("Unterminated list")
                if (text[position] == ']') {
                    position++
                    return makeList(values)
                }
                if (text[position] == ',') {
                    position++
                    continue
                }
                values += parseValue(depth)
                skipSpaceAndComments()
                if (!atEnd() && text[position] == ',') position++
            }
        }

        private fun parseTypedArray(type: Char, depth: Int): NbtTag {
            val values = ArrayList<NbtTag>()
            while (true) {
                skipSpaceAndComments()
                if (atEnd()) error("Unterminated ${type.uppercaseChar()} array")
                if (text[position] == ']') {
                    position++
                    return try {
                        when (type) {
                            'b' -> NbtByteArray(ByteArray(values.size) { index -> values[index].asByteForArray(index) })
                            'i' -> NbtIntArray(IntArray(values.size) { index -> values[index].asIntForArray(index) })
                            'l' -> NbtLongArray(LongArray(values.size) { index -> values[index].asLongForArray(index) })
                            else -> error("Unsupported typed array '$type'")
                        }
                    } catch (exception: FtbSnbtSyntaxException) {
                        error(exception.message ?: "Invalid typed array")
                    }
                }
                if (text[position] == ',') {
                    position++
                    continue
                }
                values += parseValue(depth)
                skipSpaceAndComments()
                if (!atEnd() && text[position] == ',') position++
            }
        }

        private fun makeList(values: List<NbtTag>): NbtList<*> {
            if (values.isEmpty()) return NbtList.of<NbtTag>()
            val type = values.first().javaClass
            if (values.any { it.javaClass != type }) error("List elements must all have the same NBT type")
            return when (val first = values.first()) {
                is NbtByte -> NbtList(values.map { it as NbtByte })
                is NbtShort -> NbtList(values.map { it as NbtShort })
                is NbtInt -> NbtList(values.map { it as NbtInt })
                is NbtLong -> NbtList(values.map { it as NbtLong })
                is NbtFloat -> NbtList(values.map { it as NbtFloat })
                is NbtDouble -> NbtList(values.map { it as NbtDouble })
                is NbtString -> NbtList(values.map { it as NbtString })
                is NbtByteArray -> NbtList(values.map { it as NbtByteArray })
                is NbtIntArray -> NbtList(values.map { it as NbtIntArray })
                is NbtLongArray -> NbtList(values.map { it as NbtLongArray })
                is NbtList<*> -> NbtList(values.map { it as NbtList<*> })
                is NbtCompound -> NbtList(values.map { it as NbtCompound })
            }
        }

        private fun parseWord(isKey: Boolean): String {
            val start = position
            while (!atEnd() && text[position].isFtbSimpleCharacter()) {
                position++
            }
            if (position == start) error("Expected ${if (isKey) "a compound key" else "a value"}")
            return text.substring(start, position)
        }

        private fun parseQuoted(quote: Char): String {
            position++
            val result = StringBuilder()
            while (!atEnd()) {
                val c = text[position++]
                if (c == '\n' || c == '\r') error("New line in quoted string")
                if (c == quote) return result.toString()
                if (c != '\\') {
                    result.append(c)
                    continue
                }
                if (atEnd()) error("Unterminated escape sequence")
                val escaped = text[position++]
                result.append(
                    when (escaped) {
                        '"' -> '"'
                        '\\' -> '\\'
                        '\'' -> '\''
                        't' -> '\t'
                        'b' -> '\b'
                        'n' -> '\n'
                        'r' -> '\r'
                        'f' -> '\u000C'
                        else -> error("Unsupported escape sequence \\$escaped")
                    }
                )
            }
            error("Unterminated quoted string")
        }

        private fun parseScalar(raw: String): NbtTag {
            if (raw == "true") return NbtByte(1)
            if (raw == "false") return NbtByte(0)
            if (raw == "null" || raw.equals("end", ignoreCase = true)) error("Null/end tags are not valid NBT values")

            val infinity = when (raw) {
                "Infinity", "+Infinity", "∞", "+∞", "Infinityd", "+Infinityd", "∞d", "+∞d" -> NbtDouble(Double.POSITIVE_INFINITY)
                "-Infinity", "-∞", "-Infinityd", "-∞d" -> NbtDouble(Double.NEGATIVE_INFINITY)
                "NaN", "NaNd" -> NbtDouble(Double.NaN)
                "Infinityf", "+Infinityf", "∞f", "+∞f" -> NbtFloat(Float.POSITIVE_INFINITY)
                "-Infinityf", "-∞f" -> NbtFloat(Float.NEGATIVE_INFINITY)
                "NaNf" -> NbtFloat(Float.NaN)
                else -> null
            }
            if (infinity != null) return infinity

            val suffix = raw.lastOrNull()?.lowercaseChar()
            val numericBody = raw.dropLast(1)
            return when (suffix) {
                'b' -> if (numericBody.matches(INTEGER_PATTERN) && numericBody.toIntOrNull() != null) {
                    NbtByte(numericBody.toByteOrNull() ?: error("Byte value '$raw' is out of range"))
                } else NbtString(raw)
                's' -> if (numericBody.matches(INTEGER_PATTERN) && numericBody.toIntOrNull() != null) {
                    NbtShort(numericBody.toShortOrNull() ?: error("Short value '$raw' is out of range"))
                } else NbtString(raw)
                'l' -> if (numericBody.matches(INTEGER_PATTERN) && numericBody.toLongOrNull() != null) {
                    NbtLong(numericBody.toLongOrNull() ?: error("Long value '$raw' is out of range"))
                } else NbtString(raw)
                'f' -> if (numericBody.toFloatOrNull() != null) {
                    NbtFloat(numericBody.toFloatOrNull() ?: error("Invalid float value '$raw'"))
                } else NbtString(raw)
                'd' -> if (numericBody.toDoubleOrNull() != null) {
                    NbtDouble(numericBody.toDoubleOrNull() ?: error("Invalid double value '$raw'"))
                } else NbtString(raw)
                else -> if (raw.matches(INTEGER_PATTERN)) {
                    raw.toIntOrNull()?.let(::NbtInt)
                        ?: raw.toDoubleOrNull()?.let(::NbtDouble)
                        ?: NbtString(raw)
                } else if (raw.matches(DOUBLE_PATTERN)) {
                    raw.toDoubleOrNull()?.let(::NbtDouble) ?: NbtString(raw)
                } else {
                    NbtString(raw)
                }
            }
        }

        private fun line(): Int = text.substring(0, position.coerceAtMost(text.length)).count { it == '\n' } + 1

    private fun column(): Int {
        val lastNewline = text.lastIndexOf('\n', (position - 1).coerceAtLeast(0))
        return position - lastNewline
    }
}
}

private fun NbtTag.asByteForArray(index: Int): Byte = when (this) {
    is NbtByte -> value
    is NbtShort -> value.toByteIfExact(index)
    is NbtInt -> value.toByteIfExact(index)
    is NbtLong -> value.toByteIfExact(index)
    is NbtFloat -> value.toDouble().toByteIfExact(index)
    is NbtDouble -> value.toByteIfExact(index)
    else -> throw FtbSnbt.FtbSnbtSyntaxException("Array element $index is not numeric")
}

private fun Char.isFtbSimpleCharacter(): Boolean =
    Character.isAlphabetic(code) || Character.isDigit(this) || this in "._-+∞"

private fun NbtTag.asIntForArray(index: Int): Int = when (this) {
    is NbtByte -> value.toInt()
    is NbtShort -> value.toInt()
    is NbtInt -> value
    is NbtLong -> value.toIntIfExact(index)
    is NbtFloat -> value.toDouble().toIntIfExact(index)
    is NbtDouble -> value.toIntIfExact(index)
    else -> throw FtbSnbt.FtbSnbtSyntaxException("Array element $index is not numeric")
}

private fun NbtTag.asLongForArray(index: Int): Long = when (this) {
    is NbtByte -> value.toLong()
    is NbtShort -> value.toLong()
    is NbtInt -> value.toLong()
    is NbtLong -> value
    is NbtFloat -> value.toDouble().toLongIfExact(index)
    is NbtDouble -> value.toLongIfExact(index)
    else -> throw FtbSnbt.FtbSnbtSyntaxException("Array element $index is not numeric")
}

private fun Short.toByteIfExact(index: Int): Byte = toLong().toByteIfExact(index)
private fun Int.toByteIfExact(index: Int): Byte = toLong().toByteIfExact(index)
private fun Long.toByteIfExact(index: Int): Byte {
    if (this < Byte.MIN_VALUE || this > Byte.MAX_VALUE) {
        throw FtbSnbt.FtbSnbtSyntaxException("Array element $index does not fit in a byte")
    }
    return toByte()
}

private fun Double.toByteIfExact(index: Int): Byte {
    if (!isFinite() || this % 1.0 != 0.0 || this < Byte.MIN_VALUE || this > Byte.MAX_VALUE) {
        throw FtbSnbt.FtbSnbtSyntaxException("Array element $index does not fit in a byte")
    }
    return toInt().toByte()
}

private fun Long.toIntIfExact(index: Int): Int {
    if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
        throw FtbSnbt.FtbSnbtSyntaxException("Array element $index does not fit in an integer")
    }
    return toInt()
}

private fun Double.toIntIfExact(index: Int): Int {
    if (!isFinite() || this % 1.0 != 0.0 || this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
        throw FtbSnbt.FtbSnbtSyntaxException("Array element $index does not fit in an integer")
    }
    return toInt()
}

private fun Double.toLongIfExact(index: Int): Long {
    // The upper bound is exclusive because Long.MAX_VALUE rounds to 2^63 as a Double.
    if (!isFinite() || this % 1.0 != 0.0 || this < Long.MIN_VALUE.toDouble() || this >= 9223372036854775808.0) {
        throw FtbSnbt.FtbSnbtSyntaxException("Array element $index does not fit in a long")
    }
    return toLong()
}

@Serializable(with = FtbSnbtDataSerializer::class)
public data class FtbSnbtData(
    val nbt: NbtCompound = NbtCompound(emptyMap()),
)

public object FtbSnbtDataSerializer : KSerializer<FtbSnbtData> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "calebxzau.rdi.quests.ftb.snbt.FtbSnbtData",
        PrimitiveKind.STRING,
    )

    override fun serialize(encoder: Encoder, value: FtbSnbtData) {
        encoder.encodeString(CanonicalWriter.write(value.nbt))
    }

    override fun deserialize(decoder: Decoder): FtbSnbtData {
        val result = FtbSnbt.parse(decoder.decodeString()).getOrThrow()
        return FtbSnbtData(result)
    }

    private object CanonicalWriter {
        fun write(value: NbtCompound): String = writeCompound(value)

        private fun writeTag(tag: NbtTag): String = when (tag) {
            is NbtByte -> "${tag.value}b"
            is NbtShort -> "${tag.value}s"
            is NbtInt -> tag.value.toString()
            is NbtLong -> "${tag.value}L"
            is NbtFloat -> "${tag.value}f"
            is NbtDouble -> "${tag.value}d"
            is NbtString -> quote(tag.value)
            is NbtByteArray -> tag.joinToString(",", prefix = "[B;", postfix = "]") { "${it}b" }
            is NbtIntArray -> tag.joinToString(",", prefix = "[I;", postfix = "]")
            is NbtLongArray -> tag.joinToString(",", prefix = "[L;", postfix = "]") { "${it}L" }
            is NbtList<*> -> tag.joinToString(",", prefix = "[", postfix = "]") { writeTag(it) }
            is NbtCompound -> writeCompound(tag)
        }

        private fun writeCompound(compound: NbtCompound): String = buildString {
            append('{')
            compound.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) append(',')
                append(quote(key))
                append(':')
                append(writeTag(value))
            }
            append('}')
        }

        private fun quote(value: String): String = buildString(value.length + 2) {
            append('"')
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\u000C' -> append("\\f")
                    else -> append(character)
                }
            }
            append('"')
        }
    }
}
