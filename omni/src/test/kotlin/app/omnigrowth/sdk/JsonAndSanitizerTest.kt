package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.util.Date
import java.util.UUID

class JsonAndSanitizerTest {
    @Test
    fun writesJsonWithoutEscapingSlashes() {
        assertEquals("""{"url":"https://a.b/c","q":"say \"hi\"\n","n":3,"d":1.5,"b":true,"z":null}""",
            Json.encode(linkedMapOf("url" to "https://a.b/c", "q" to "say \"hi\"\n", "n" to 3L, "d" to 1.5, "b" to true, "z" to null)))
        assertEquals("[1,2.25,\"\\u0001\"]", Json.encode(listOf(1, 2.25, "\u0001")))
        assertEquals("3", Json.encode(3.0))
    }

    @Test
    fun parsesWhatItWritesAndRejectsGarbage() {
        val value = linkedMapOf("a" to listOf(1L, 2.5, "x", null, true), "b" to linkedMapOf("c" to "ü \u2603 \"q\""))
        assertEquals(value, Json.parse(Json.encode(value)))
        assertEquals("é/", Json.parse("\"\\u00e9\\/\""))
        assertEquals(-1.0e-5, Json.parse("-1e-5"))
        assertNull(Json.parse("{"))
        assertNull(Json.parse("{\"a\":1} x"))
        assertNull(Json.parse("nul"))
        assertNull(Json.parse(""))
    }

    @Test
    fun sanitizerKeepsJsonSafeValuesAndDropsTheRest() {
        val date = Date(Timestamp.parse("2026-10-21T08:59:58.120Z")!!)
        val uuid = UUID.fromString("6f1c2d3e-4b5a-4c6d-8e7f-0123456789ab")
        val out = Sanitizer.properties(
            mapOf(
                "int" to 3, "long" to 4L, "float" to 1.5f, "big" to BigDecimal("2.5"), "bool" to false, "s" to "x",
                "date" to date, "uuid" to uuid, "list" to listOf(1, "a", Any()), "map" to mapOf("k" to 1, 2 to "skip"),
                "nan" to Double.NaN, "obj" to Any(), "null" to null, "char" to 'c',
            ),
        )
        assertEquals(3L, out["int"])
        assertEquals(4L, out["long"])
        assertEquals(1.5, out["float"])
        assertEquals(2.5, out["big"])
        assertEquals(false, out["bool"])
        assertEquals("2026-10-21T08:59:58.120Z", out["date"])
        assertEquals(uuid.toString(), out["uuid"])
        assertEquals(listOf(1L, "a"), out["list"])
        assertEquals(mapOf("k" to 1L), out["map"])
        assertEquals("c", out["char"])
        assertTrue(out.containsKey("null"))
        assertFalse(out.containsKey("nan"))
        assertFalse(out.containsKey("obj"))
    }

    @Test
    fun sanitizerAppliesProtocolLimits() {
        val big = Sanitizer.properties(mapOf("big" to "x".repeat(1100), "ok" to "y"))
        assertEquals(setOf("ok"), big.keys)
        val many = Sanitizer.properties((0 until 150).associate { "k%03d".format(it) to it })
        assertEquals(100, many.size)
    }

    @Test
    fun timestampsFormatAndParse() {
        val t = Timestamp.parse("2026-10-21T08:59:58.120Z")!!
        assertEquals("2026-10-21T08:59:58.120Z", Timestamp.format(t))
        assertEquals(t, Timestamp.parse("2026-10-21T17:59:58.120+09:00"))
        assertEquals(Timestamp.parse("2026-10-22T00:00:00.000Z"), Timestamp.parse("2026-10-22"))
        assertNull(Timestamp.parse("yesterday"))
        assertNull(Timestamp.parse("2026-13-45T00:00:00Z"))
    }
}
