package com.deepseek.dshrider.wire

/**
 * Minimal JSON parser/builder for the DeepSeek Harness HTTP wire protocol.
 * Self-contained on purpose: no third-party JSON dependency, parses the full
 * JSON grammar (strings with escapes and surrogate pairs, numbers, booleans,
 * null, arrays, objects).
 */

sealed class JsonValue {
    object Null : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Double) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Obj(val fields: LinkedHashMap<String, JsonValue>) : JsonValue()

    fun asObj(): Obj? = this as? Obj
    fun asArr(): Arr? = this as? Arr
    fun asStr(): String? = (this as? Str)?.value
    fun asNum(): Double? = (this as? Num)?.value
    fun asBool(): Boolean? = (this as? Bool)?.value
    fun asLongOrNull(): Long? = asNum()?.takeIf { it.isFinite() }?.toLong()
    fun asIntOrNull(): Int? = asNum()?.takeIf { it.isFinite() }?.toInt()

    fun obj(name: String): JsonValue? = asObj()?.fields?.get(name)
    fun str(name: String, default: String = ""): String = obj(name)?.asStr() ?: default
    fun num(name: String, default: Double = 0.0): Double = obj(name)?.asNum() ?: default
    fun bool(name: String, default: Boolean = false): Boolean = obj(name)?.asBool() ?: default
    fun long(name: String, default: Long = 0L): Long = obj(name)?.asLongOrNull() ?: default
    fun arr(name: String): List<JsonValue> = obj(name)?.asArr()?.items ?: emptyList()
    fun objField(name: String): JsonValue.Obj? = obj(name)?.asObj()
}

class JsonParseException(message: String) : Exception(message)

object MiniJson {

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw JsonParseException("trailing content at offset ${p.pos}")
        return value
    }

    /** Parse or return null on any failure (wire data is intentionally wide). */
    fun parseOrNull(text: String?): JsonValue? = try {
        if (text == null) null else parse(text)
    } catch (_: Exception) {
        null
    }

    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    fun quoted(s: String): String = "\"${escape(s)}\""

    private class Parser(private val src: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> if (c == '-' || c in '0'..'9') parseNumber() else
                    throw JsonParseException("unexpected char '$c' at offset $pos")
            }
        }

        private fun expect(word: String) {
            if (pos + word.length > src.length || src.substring(pos, pos + word.length) != word)
                throw JsonParseException("expected '$word' at offset $pos")
            pos += word.length
        }

        private fun parseObject(): JsonValue.Obj {
            pos++ // {
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (!atEnd() && src[pos] == '}') { pos++; return JsonValue.Obj(map) }
            while (true) {
                skipWhitespace()
                if (atEnd() || src[pos] != '"') throw JsonParseException("expected key string at offset $pos")
                val key = parseString()
                skipWhitespace()
                if (atEnd() || src[pos] != ':') throw JsonParseException("expected ':' at offset $pos")
                pos++
                map[key] = parseValue()
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(map) }
                    else -> throw JsonParseException("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            pos++ // [
            val list = ArrayList<JsonValue>()
            skipWhitespace()
            if (!atEnd() && src[pos] == ']') { pos++; return JsonValue.Arr(list) }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (atEnd()) throw JsonParseException("unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(list) }
                    else -> throw JsonParseException("expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                val c = src[pos]
                when {
                    c == '"' -> { pos++; return sb.toString() }
                    c == '\\' -> {
                        pos++
                        if (atEnd()) throw JsonParseException("bad escape")
                        when (val e = src[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= src.length) throw JsonParseException("bad \\u escape")
                                val hex = src.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16) ?: throw JsonParseException("bad \\u escape")
                                pos += 4
                                if (code in 0xD800..0xDBFF && pos + 6 < src.length && src[pos + 1] == '\\' && src[pos + 2] == 'u') {
                                    val lowHex = src.substring(pos + 3, pos + 7)
                                    val low = lowHex.toIntOrNull(16)
                                    if (low != null && low in 0xDC00..0xDFFF) {
                                        sb.appendCodePoint(0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00))
                                        pos += 6
                                    } else sb.append(code.toChar())
                                } else sb.append(code.toChar())
                            }
                            else -> throw JsonParseException("bad escape '\\$e'")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (!atEnd() && src[pos] == '-') pos++
            while (!atEnd() && src[pos] in '0'..'9') pos++
            if (!atEnd() && src[pos] == '.') {
                pos++
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (src[pos] == 'e' || src[pos] == 'E')) {
                pos++
                if (!atEnd() && (src[pos] == '+' || src[pos] == '-')) pos++
                while (!atEnd() && src[pos] in '0'..'9') pos++
            }
            val text = src.substring(start, pos)
            val value = text.toDoubleOrNull() ?: throw JsonParseException("bad number '$text'")
            return JsonValue.Num(value)
        }
    }
}
