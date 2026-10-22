package app.omnigrowth.sdk

import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * A small JSON reader and writer. Values are `null`, [Boolean], [Long], [Double], [String], [List] and [Map] with
 * string keys (maps keep their order). Written by hand rather than org.json so the device and the JVM tests behave
 * the same, and slashes aren't escaped (like the Swift SDK).
 */
internal object Json {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    /** The parsed value, or null when [text] isn't valid JSON. */
    fun parse(text: String): Any? = try {
        Parser(text).parseDocument()
    } catch (_: IllegalArgumentException) {
        null
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?>? = parse(text) as? Map<String, Any?>

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is Long, is Int, is Short, is Byte -> out.append(value.toString())
            is Double -> writeDouble(out, value)
            is Float -> writeDouble(out, value.toDouble())
            is String -> writeString(out, value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k.toString())
                    out.append(':')
                    write(out, v)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(out, v)
                }
                out.append(']')
            }
            else -> writeString(out, value.toString())
        }
    }

    private fun writeDouble(out: StringBuilder, d: Double) {
        when {
            d.isNaN() || d.isInfinite() -> out.append("null")
            d == Math.floor(d) && Math.abs(d) < 1e15 -> out.append(d.toLong().toString())
            else -> out.append(d.toString())
        }
    }

    private fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    out.append(String.format(Locale.US, "\\u%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseDocument(): Any? {
            val v = value(0)
            skipWhitespace()
            require(i == s.length) { "trailing characters" }
            return v
        }

        private fun value(depth: Int): Any? {
            require(depth < 64) { "too deep" }
            skipWhitespace()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw IllegalArgumentException("unexpected $c")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            i++
            skipWhitespace()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "expected a key" }
                val key = string()
                skipWhitespace()
                require(peek() == ':') { "expected :" }
                i++
                map[key] = value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return map
                    }
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++
            skipWhitespace()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                list.add(value(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return list
                    }
                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        private fun string(): String {
            i++ // opening quote
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(i < s.length) { "bad escape" }
                        when (val e = s[i++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad unicode escape" }
                                out.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            val integral = text.none { it == '.' || it == 'e' || it == 'E' }
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: throw IllegalArgumentException("bad number $text")
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "unexpected literal" }
            i += word.length
            return value
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }
    }
}

/**
 * Turns arbitrary values from the host app into JSON-safe ones, within the protocol's limits
 * (≤ 100 keys, each value ≤ 1 KB as JSON). Dates become ISO 8601 strings; anything that can't be encoded is dropped.
 */
internal object Sanitizer {
    const val MAX_KEYS = 100
    const val MAX_VALUE_BYTES = 1024
    private const val MAX_DEPTH = 16

    fun properties(map: Map<String, Any?>?, log: Log = Log.off): Map<String, Any?> {
        if (map.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (key in map.keys.sorted()) {
            val raw = map[key]
            val value = sanitize(raw)
            if (value === Dropped) {
                log("dropped property \"$key\": ${raw?.javaClass?.simpleName} isn't JSON-encodable")
                continue
            }
            if ((value is Map<*, *> || value is List<*> || value is String) &&
                Json.encode(value).toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES
            ) {
                log("dropped property \"$key\": larger than 1 KB")
                continue
            }
            if (out.size >= MAX_KEYS) {
                log("dropped properties after the first $MAX_KEYS keys")
                break
            }
            out[key] = value
        }
        return out
    }

    /** Marks a value that can't be represented (distinct from a JSON null). */
    private object Dropped

    private fun sanitize(v: Any?, depth: Int = 0): Any? {
        if (depth > MAX_DEPTH) return Dropped
        return when (v) {
            null -> null
            is String -> v
            is Boolean -> v
            is Int -> v.toLong()
            is Long -> v
            is Short -> v.toLong()
            is Byte -> v.toLong()
            is Double -> if (v.isNaN() || v.isInfinite()) Dropped else v
            is Float -> if (v.isNaN() || v.isInfinite()) Dropped else v.toDouble()
            is Number -> v.toDouble().takeUnless { it.isNaN() || it.isInfinite() } ?: Dropped
            is Char -> v.toString()
            is CharSequence -> v.toString()
            is Date -> Timestamp.format(v.time)
            is Calendar -> Timestamp.format(v.timeInMillis)
            is UUID, is URI, is URL -> v.toString()
            is Enum<*> -> v.name
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                for ((k, value) in v) {
                    if (k !is String) continue
                    val s = sanitize(value, depth + 1)
                    if (s !== Dropped) out[k] = s
                }
                out
            }
            is Iterable<*> -> v.map { sanitize(it, depth + 1) }.filter { it !== Dropped }
            is Array<*> -> v.map { sanitize(it, depth + 1) }.filter { it !== Dropped }
            is IntArray -> v.map { it.toLong() }
            is LongArray -> v.toList()
            is DoubleArray -> v.filter { !it.isNaN() && !it.isInfinite() }
            is BooleanArray -> v.toList()
            else -> if (v.javaClass.name == "android.net.Uri" || v.javaClass.superclass?.name == "android.net.Uri") {
                v.toString()
            } else {
                Dropped
            }
        }
    }
}

/** ISO 8601 UTC timestamps with milliseconds (`2026-10-21T08:59:58.120Z`). */
internal object Timestamp {
    private val utc = TimeZone.getTimeZone("UTC")

    fun format(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = utc
        return f.format(Date(millis))
    }

    private val iso = Regex(
        """^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?(Z|[+-]\d{2}:?\d{2})?$""",
    )

    /** Milliseconds since 1970 for an ISO 8601 date or date-time, or null. A missing zone is read as UTC. */
    fun parse(text: String): Long? {
        val m = iso.matchEntire(text.trim()) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance(utc, Locale.US)
        cal.clear()
        cal.isLenient = false
        return try {
            cal.set(
                g[1].toInt(), g[2].toInt() - 1, g[3].toInt(),
                g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt(),
            )
            val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.substring(0, 3)?.toInt() ?: 0
            cal.set(Calendar.MILLISECOND, millis)
            var t = cal.timeInMillis
            val zone = g[8]
            if (zone.isNotEmpty() && zone != "Z") {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
                t -= sign * offset
            }
            t
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Debug logging (`Options.debug`); warnings always show. */
internal class Log(
    val enabled: Boolean,
    private val sink: (String) -> Unit = { android.util.Log.d("Omni", it) },
) {
    operator fun invoke(message: String) {
        if (enabled) sink(message)
    }

    companion object {
        val off = Log(false) {}

        fun warn(message: String) {
            try {
                android.util.Log.w("Omni", message)
            } catch (_: Throwable) {
                println("[Omni] $message")
            }
        }
    }
}
