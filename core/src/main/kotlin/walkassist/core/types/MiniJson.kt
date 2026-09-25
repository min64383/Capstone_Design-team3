package walkassist.core.types

/** JSON 값 트리. 설정 파일 읽기 전용으로 쓰는 최소 구현이다(core는 외부 라이브러리를 쓰지 않는다). */
sealed interface JsonValue

/** 키 순서를 보존하는 JSON 객체. */
data class JsonObject(val fields: Map<String, JsonValue>) : JsonValue

/** JSON 배열. */
data class JsonArray(val items: List<JsonValue>) : JsonValue

/** JSON 숫자. 정수·실수 구분 없이 Double로 보관한다. */
data class JsonNumber(val value: Double) : JsonValue

/** JSON 문자열. */
data class JsonString(val value: String) : JsonValue

/** JSON 불리언. */
data class JsonBool(val value: Boolean) : JsonValue

/** JSON null. */
data object JsonNull : JsonValue

/** JSON 구문 오류. [offset]은 입력 문자열에서의 위치. */
class JsonParseException(message: String, val offset: Int) : IllegalArgumentException("$message (offset $offset)")

/** RFC 8259 JSON 파서. 중복 키는 설정 실수를 숨기므로 오류로 처리한다. */
object MiniJson {

    /** [text] 전체를 하나의 JSON 값으로 파싱한다. 뒤에 남는 문자가 있으면 오류. */
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw p.error("unexpected trailing characters")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun error(msg: String) = JsonParseException(msg, i)

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(): JsonValue {
            if (atEnd()) throw error("unexpected end of input")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> JsonString(str())
                't' -> literal("true", JsonBool(true))
                'f' -> literal("false", JsonBool(false))
                'n' -> literal("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') num() else throw error("unexpected character '$c'")
            }
        }

        private fun literal(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, i)) throw error("invalid literal")
            i += word.length
            return v
        }

        private fun obj(): JsonObject {
            i++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return JsonObject(fields) }
            while (true) {
                skipWs()
                if (atEnd() || s[i] != '"') throw error("expected object key")
                val keyAt = i
                val key = str()
                if (key in fields) throw JsonParseException("duplicate key '$key'", keyAt)
                skipWs()
                expect(':')
                skipWs()
                fields[key] = value()
                skipWs()
                if (atEnd()) throw error("unterminated object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return JsonObject(fields) }
                    else -> throw error("expected ',' or '}'")
                }
            }
        }

        private fun arr(): JsonArray {
            i++ // '['
            val items = ArrayList<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return JsonArray(items) }
            while (true) {
                skipWs()
                items += value()
                skipWs()
                if (atEnd()) throw error("unterminated array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return JsonArray(items) }
                    else -> throw error("expected ',' or ']'")
                }
            }
        }

        private fun expect(c: Char) {
            if (atEnd() || s[i] != c) throw error("expected '$c'")
            i++
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw error("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) throw error("unterminated escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw error("truncated \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw error("invalid \\u escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw error("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw error("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): JsonNumber {
            val start = i
            if (s[i] == '-') i++
            if (atEnd()) throw error("invalid number")
            if (s[i] == '0') {
                i++
            } else if (s[i] in '1'..'9') {
                while (i < s.length && s[i].isAsciiDigit()) i++
            } else {
                throw error("invalid number")
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (atEnd() || !s[i].isAsciiDigit()) throw error("invalid fraction")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (atEnd() || !s[i].isAsciiDigit()) throw error("invalid exponent")
                while (i < s.length && s[i].isAsciiDigit()) i++
            }
            return JsonNumber(s.substring(start, i).toDouble())
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
