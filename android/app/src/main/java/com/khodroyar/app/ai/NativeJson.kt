package com.khodroyar.app.ai

import org.json.JSONObject

/**
 * Strict JSON syntax and depth/size bounds BEFORE org.json's recursive parser.
 * Android's JSONObject accepts comments, bare names, duplicate keys and trailing commas;
 * none of those may silently change the meaning of a native tool argument or price.
 */
internal object NativeJson {
    fun objectValue(text: String, limit: Int): JSONObject {
        require(text.length <= limit)
        Syntax(text).root()
        return JSONObject(text)
    }

    private class Syntax(private val text: String) {
        private var position = 0
        private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
        private fun space() { while (peek() in listOf(' ', '\t', '\r', '\n')) position++ }
        private fun take(char: Char): Boolean = (peek() == char).also { if (it) position++ }
        private fun expect(char: Char) { require(take(char)) }
        fun root() {
            space(); objectValue(1); space(); require(position == text.length)
        }
        private fun value(depth: Int) {
            space()
            when (peek()) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> numberValue()
            }
        }
        private fun objectValue(depth: Int) {
            require(depth <= 32); expect('{'); space()
            if (take('}')) return
            val keys = hashSetOf<String>()
            while (true) {
                require(keys.add(stringValue()))
                space(); expect(':'); value(depth + 1); space()
                if (take('}')) return
                expect(','); space()
            }
        }
        private fun arrayValue(depth: Int) {
            require(depth <= 32); expect('['); space()
            if (take(']')) return
            while (true) {
                value(depth + 1); space()
                if (take(']')) return
                expect(','); space()
            }
        }
        private fun literal(value: String) {
            require(text.startsWith(value, position)); position += value.length
        }
        private fun numberValue() {
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
