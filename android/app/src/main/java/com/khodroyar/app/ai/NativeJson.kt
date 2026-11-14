package com.khodroyar.app.ai

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * Bounded, strict JSON. Android's JSONObject accepts ambiguous syntax and parses
 * decimal numbers through Double: a positive 1e-1000 price/cost becomes zero.
 * Preserve decimal tokens exactly, including on Android, before any free-price or
 * integer-argument validation. This parser never interprets prose as an operation.
 */
internal object NativeJson {
    fun objectValue(text: String, limit: Int): JSONObject {
        require(text.length <= limit)
        return Syntax(text).root()
    }

    private class Syntax(private val text: String) {
        private var position = 0
        private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
        private fun space() {
            while (true) when (peek()) { ' ', '\t', '\r', '\n' -> position++; else -> return }
        }
        private fun take(char: Char): Boolean = (peek() == char).also { if (it) position++ }
        private fun expect(char: Char) { require(take(char)) }
        fun root(): JSONObject {
            space(); val result = objectValue(1); space(); require(position == text.length)
            return result
        }
        private fun value(depth: Int): Any {
            space()
            return when (peek()) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> { literal("true"); true }
                'f' -> { literal("false"); false }
                'n' -> { literal("null"); JSONObject.NULL }
                else -> numberValue()
            }
        }
        private fun objectValue(depth: Int): JSONObject {
            require(depth <= 32); expect('{'); space()
            val result = JSONObject(); val keys = hashSetOf<String>()
            if (take('}')) return result
            while (true) {
                val key = stringValue(); require(keys.add(key))
                space(); expect(':'); result.put(key, value(depth + 1)); space()
                if (take('}')) return result
                expect(','); space()
            }
        }
        private fun arrayValue(depth: Int): JSONArray {
            require(depth <= 32); expect('['); space()
            val result = JSONArray()
            if (take(']')) return result
            while (true) {
                result.put(value(depth + 1)); space()
                if (take(']')) return result
                expect(','); space()
            }
        }
        private fun literal(value: String) {
            require(text.startsWith(value, position)); position += value.length
        }
        private fun numberValue(): Number {
            val start = position
            take('-')
            if (take('0')) require(peek() !in '0'..'9') else {
                require(peek() in '1'..'9')
                while (peek() in '0'..'9') position++
            }
            if (take('.')) {
                require(peek() in '0'..'9')
                while (peek() in '0'..'9') position++
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                require(peek() in '0'..'9')
                while (peek() in '0'..'9') position++
            }
            require(position - start <= 1000)
            val token = text.substring(start, position)
            return token.toLongOrNull() ?: BigDecimal(token)
        }
        private fun stringValue(): String {
            expect('"')
            val out = StringBuilder()
            while (!take('"')) {
                require(position < text.length)
                val char = text[position++]
                require(char >= ' ')
                if (char != '\\') out.append(char) else {
                    require(position < text.length)
                    out.append(when (val escape = text[position++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            require(position + 4 <= text.length)
                            val code = text.substring(position, position + 4)
                            require(code.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            position += 4; code.toInt(16).toChar()
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape")
                    })
                }
            }
            return out.toString()
        }
    }
}
