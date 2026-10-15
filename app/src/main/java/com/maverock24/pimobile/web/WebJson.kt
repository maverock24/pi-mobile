package com.maverock24.pimobile.web

/**
 * A small JSON tree, parser and writer for the relay.
 *
 * The relay emits a few tiny documents and reads one reduced upstream payload.
 * Android ships `org.json`, but that class throws on a plain JVM and every
 * security check in this package has to be testable without a phone. Rather than
 * pull a JSON library in for a dozen fields, this file carries the smallest
 * recursive-descent parser and writer that can do the job, so the server and its
 * tests share one dependency-free implementation.
 */
internal sealed interface JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue

    /** The field as a string, or null when it is absent or not a string. */
    fun stringOrNull(key: String): String? = (this as? Obj)?.fields?.get(key).let {
        (it as? Str)?.value
    }

    /** The field as a boolean, or null when it is absent or not a boolean. */
    fun boolOrNull(key: String): Boolean? = (this as? Obj)?.fields?.get(key).let {
        (it as? Bool)?.value
    }
}

internal fun jsonObject(vararg fields: Pair<String, JsonValue>): JsonValue.Obj =
    JsonValue.Obj(fields.toMap())

internal fun jsonString(value: String): JsonValue.Str = JsonValue.Str(value)

internal fun jsonBool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)

/** Serialises this value. Strings are escaped; numbers are emitted verbatim. */
internal fun JsonValue.render(): String = StringBuilder().also { write(it) }.toString()

private fun JsonValue.write(out: StringBuilder) {
    when (this) {
        is JsonValue.Obj -> {
            out.append('{')
            fields.entries.forEachIndexed { index, (key, value) ->
                if (index > 0) out.append(',')
                writeString(out, key)
                out.append(':')
                value.write(out)
            }
            out.append('}')
        }
        is JsonValue.Arr -> {
            out.append('[')
            items.forEachIndexed { index, value ->
                if (index > 0) out.append(',')
                value.write(out)
            }
            out.append(']')
        }
        is JsonValue.Str -> writeString(out, value)
        is JsonValue.Num -> out.append(raw)
        is JsonValue.Bool -> out.append(if (value) "true" else "false")
        JsonValue.Null -> out.append("null")
    }
}

private fun writeString(out: StringBuilder, value: String) {
    out.append('"')
    for (character in value) {
        when {
            character == '"' -> out.append("\\\"")
            character == '\\' -> out.append("\\\\")
            character == '\n' -> out.append("\\n")
            character == '\r' -> out.append("\\r")
            character == '\t' -> out.append("\\t")
            character < ' ' -> out.append("\\u%04x".format(character.code))
            else -> out.append(character)
        }
    }
    out.append('"')
}

/**
 * Parses [text] into a tree. Throws [IllegalArgumentException] on anything
 * malformed, so a caller that hands it a bad upstream body fails loudly rather
 * than returning a half document.
 */
internal fun parseJson(text: String): JsonValue = JsonParser(text).parse()

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): JsonValue {
        val value = parseValue()
        skipWhitespace()
        if (index != text.length) fail("trailing input")
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (index >= text.length) fail("unexpected end")
        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't' -> JsonValue.Bool(parseLiteral("true"))
            'f' -> JsonValue.Bool(!parseLiteral("false"))
            'n' -> { parseLiteral("null"); JsonValue.Null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): JsonValue.Obj {
        index++ // {
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> { index++; return JsonValue.Obj(fields) }
                else -> fail("expected , or }")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        index++ // [
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> { index++; return JsonValue.Arr(items) }
                else -> fail("expected , or ]")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (index >= text.length) fail("unterminated string")
            val character = text[index++]
            when {
                character == '"' -> return builder.toString()
                character == '\\' -> builder.append(parseEscape())
                else -> builder.append(character)
            }
        }
    }

    private fun parseEscape(): Char {
        if (index >= text.length) fail("unterminated escape")
        return when (val escape = text[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) fail("short unicode escape")
                val hex = text.substring(index, index + 4)
                index += 4
                hex.toIntOrNull(16)?.toChar() ?: fail("bad unicode escape")
            }
            else -> fail("bad escape \\$escape")
        }
    }

    private fun parseLiteral(literal: String): Boolean {
        if (!text.startsWith(literal, index)) fail("expected $literal")
        index += literal.length
        return true
    }

    private fun parseNumber(): JsonValue.Num {
        val start = index
        if (peek() == '-') index++
        while (index < text.length && text[index] in "0123456789+-.eE") index++
        if (start == index) fail("expected a value")
        return JsonValue.Num(text.substring(start, index))
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun peek(): Char {
        if (index >= text.length) fail("unexpected end")
        return text[index]
    }

    private fun expect(character: Char) {
        if (index >= text.length || text[index] != character) fail("expected $character")
        index++
    }

    private fun fail(what: String): Nothing =
        throw IllegalArgumentException("invalid JSON at $index: $what")
}
