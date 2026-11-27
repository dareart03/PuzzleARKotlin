package cn.easyar.samples.helloar

// Небольшой разборщик JSON (объекты - Map, массивы - List, числа - Double, строки, Boolean, null).
// Нужен для чтения заголовка GLB; свой, чтобы его можно было проверять обычными JVM-тестами.
object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWhitespace()
        val value = p.readValue(0)
        p.skipWhitespace()
        if (p.pos != text.length) {
            throw IllegalArgumentException("лишние данные после JSON в позиции ${p.pos}")
        }
        return value
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(message: String): Nothing = throw IllegalArgumentException("$message (позиция $pos)")

        fun skipWhitespace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) {
                pos++
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > 200) fail("слишком глубокая вложенность")
            if (pos >= s.length) fail("неожиданный конец")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || (c in '0'..'9')) readNumber() else fail("неожиданный символ '$c'")
            }
        }

        fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("ожидалось $word")
            pos += word.length
            return value
        }

        fun readNumber(): Double {
            val start = pos
            while (pos < s.length && (s[pos] in '0'..'9' || s[pos] == '-' || s[pos] == '+' || s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) {
                pos++
            }
            return s.substring(start, pos).toDoubleOrNull() ?: fail("неверное число")
        }

        fun readString(): String {
            pos++ // открывающая кавычка
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("строка не закрыта")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= s.length) fail("строка не закрыта")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("неверный \\u")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("неверный \\u")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("неверный escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                skipWhitespace()
                list.add(readValue(depth + 1))
                skipWhitespace()
                if (pos >= s.length) fail("массив не закрыт")
                val c = s[pos++]
                if (c == ']') return list
                if (c != ',') fail("ожидалась запятая")
            }
        }

        fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (pos >= s.length || s[pos] != '"') fail("ожидалось имя поля")
                val key = readString()
                skipWhitespace()
                if (pos >= s.length || s[pos] != ':') fail("ожидалось двоеточие")
                pos++
                skipWhitespace()
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (pos >= s.length) fail("объект не закрыт")
                val c = s[pos++]
                if (c == '}') return map
                if (c != ',') fail("ожидалась запятая")
            }
        }
    }
}
