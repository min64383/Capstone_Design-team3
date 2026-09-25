package walkassist.core.types

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MiniJsonTest {

    @Test
    fun `parses nested structures and literals`() {
        val v = MiniJson.parse(""" { "a": [1, -2.5, 3e2, -1E-1], "b": { "c": true, "d": false, "e": null }, "f": "x" } """)
        val expected = JsonObject(
            linkedMapOf(
                "a" to JsonArray(listOf(JsonNumber(1.0), JsonNumber(-2.5), JsonNumber(300.0), JsonNumber(-0.1))),
                "b" to JsonObject(linkedMapOf("c" to JsonBool(true), "d" to JsonBool(false), "e" to JsonNull)),
                "f" to JsonString("x"),
            ),
        )
        assertEquals(expected, v)
    }

    @Test
    fun `parses empty containers`() {
        assertEquals(JsonObject(emptyMap()), MiniJson.parse("{}"))
        assertEquals(JsonArray(emptyList()), MiniJson.parse("[ ]"))
    }

    @Test
    fun `decodes string escapes`() {
        assertEquals(JsonString("a\"b\\c/d\ne\tf\u00e9한"), MiniJson.parse(""""a\"b\\c\/d\ne\tf\u00e9한""""))
    }

    @Test
    fun `rejects malformed input`() {
        val bad = listOf(
            "", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "01", "1.", "-", "1e", ".5",
            "tru", "\"abc", "\"\\x\"", "{} x", "{'a':1}", "\"a\nb\"",
        )
        for (s in bad) assertThrows<JsonParseException>("input: $s") { MiniJson.parse(s) }
    }

    @Test
    fun `rejects duplicate keys`() {
        assertThrows<JsonParseException> { MiniJson.parse("""{ "a": 1, "a": 2 }""") }
    }
}
