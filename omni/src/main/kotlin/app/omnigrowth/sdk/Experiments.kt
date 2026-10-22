package app.omnigrowth.sdk

/** 32-bit FNV-1a over UTF-8, as an unsigned value in a Long (PROTOCOL.md "Assignment"). */
internal object Fnv1a {
    private const val OFFSET_BASIS = 2166136261L
    private const val PRIME = 16777619L

    fun hash32(input: String): Long {
        var h = OFFSET_BASIS
        for (b in input.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h = (h * PRIME) and 0xffffffffL
        }
        return h
    }
}

/** Assignment, identical in every SDK: same bucket id and experiment → same variant, with no network call. */
internal object Assignment {
    fun bucket(experimentKey: String, bucketId: String): Int =
        (Fnv1a.hash32("$experimentKey:$bucketId") % 10_000).toInt()

    /**
     * Walks the variants adding `weight * 10000 / W`; the first whose running total is greater than [bucket] wins.
     * Null when no variant has a positive weight.
     */
    fun pick(weights: List<Double>, bucket: Int): Int? {
        val total = weights.sumOf { if (it > 0) it else 0.0 }
        if (total <= 0) return null
        var running = 0.0
        weights.forEachIndexed { i, w ->
            running += (if (w > 0) w else 0.0) * 10_000 / total
            if (running > bucket) return i
        }
        // Rounding can leave the total a hair under 10000: the last real variant takes the remainder.
        return weights.indexOfLast { it > 0 }
    }
}

internal class Variant(val key: String, val weight: Double, val payload: Map<String, Any?>)

internal class Experiment(
    val key: String,
    val variants: List<Variant>,
    /** `audience.platforms`, or null when every platform is in. */
    val platforms: List<String>?,
    /** `audience.new_users_since` in ms, or null when there's no such condition. Unreadable → nobody qualifies. */
    val newUsersSince: Long?,
    val winner: String?,
)

/** The answer for one experiment on this device. [decided]: the experiment has a winner, so no exposure is sent. */
internal class Choice(val variant: String, val payload: Map<String, Any?>, val decided: Boolean)

/** `GET /v1/config`. */
internal class ExperimentConfig(val experiments: List<Experiment>, val ttlSeconds: Long) {
    fun find(key: String): Experiment? = experiments.firstOrNull { it.key == key }

    companion object {
        const val DEFAULT_TTL_SECONDS = 300L

        /** The config, or null when [text] isn't one (a failed fetch then keeps the last copy). */
        fun parse(text: String): ExperimentConfig? {
            val root = Json.parseObject(text) ?: return null
            val list = root["experiments"] as? List<*> ?: return null
            val ttl = (root["ttl"] as? Number)?.toLong()?.takeIf { it > 0 } ?: DEFAULT_TTL_SECONDS
            return ExperimentConfig(list.mapNotNull { (it as? Map<*, *>)?.let(::experiment) }, ttl)
        }

        @Suppress("UNCHECKED_CAST")
        private fun experiment(m: Map<*, *>): Experiment? {
            val key = m["key"] as? String ?: return null
            val variants = (m["variants"] as? List<*>).orEmpty().mapNotNull { raw ->
                val v = raw as? Map<*, *> ?: return@mapNotNull null
                val vKey = v["key"] as? String ?: return@mapNotNull null
                Variant(
                    key = vKey,
                    weight = (v["weight"] as? Number)?.toDouble() ?: 0.0,
                    payload = (v["payload"] as? Map<String, Any?>) ?: emptyMap(),
                )
            }
            val audience = m["audience"] as? Map<*, *>
            val platforms = (audience?.get("platforms") as? List<*>)?.filterIsInstance<String>()
            val since = audience?.get("new_users_since")?.let { raw ->
                (raw as? String)?.let(Timestamp::parse) ?: Long.MAX_VALUE
            }
            return Experiment(key, variants, platforms, since, m["winner"] as? String)
        }

        /**
         * What this device gets, or null: not listed, outside the audience, or no usable variant.
         *
         * @param firstLaunchAt when this device first ran the app (null: before the SDK kept it, so an old user).
         */
        fun choose(exp: Experiment, platform: String, firstLaunchAt: Long?, bucketId: String): Choice? {
            val platformOk = exp.platforms == null || platform in exp.platforms
            if (exp.winner != null) {
                // Over: everyone on a matching platform sees the winner, enrolled or not.
                if (!platformOk) return null
                val payload = exp.variants.firstOrNull { it.key == exp.winner }?.payload ?: emptyMap()
                return Choice(exp.winner, payload, decided = true)
            }
            if (!platformOk) return null
            if (exp.newUsersSince != null && (firstLaunchAt == null || firstLaunchAt < exp.newUsersSince)) return null
            val index = Assignment.pick(exp.variants.map { it.weight }, Assignment.bucket(exp.key, bucketId)) ?: return null
            val variant = exp.variants[index]
            return Choice(variant.key, variant.payload, decided = false)
        }
    }
}
