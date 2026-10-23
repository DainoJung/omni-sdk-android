package app.omnigrowth.sdk

/** `context` of an event (PROTOCOL.md). All fields optional; absent ones are omitted. */
internal data class EventContext(
    val appVersion: String? = null,
    val build: String? = null,
    val platform: String? = null,
    val deviceType: String? = null,
    val locale: String? = null,
    /** IANA zone (`Asia/Seoul`), read per event since people travel: Omni sends pushes at their local time. */
    val timezone: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    val sessionId: String? = null,
    val sdk: String? = null,
) {
    fun toJson(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        appVersion?.let { put("app_version", it) }
        build?.let { put("build", it) }
        platform?.let { put("platform", it) }
        deviceType?.let { put("device_type", it) }
        locale?.let { put("locale", it) }
        timezone?.let { put("timezone", it) }
        osVersion?.let { put("os_version", it) }
        deviceModel?.let { put("device_model", it) }
        sessionId?.let { put("session_id", it) }
        sdk?.let { put("sdk", it) }
    }

    companion object {
        fun fromJson(m: Map<*, *>?): EventContext = EventContext(
            appVersion = m?.get("app_version") as? String,
            build = m?.get("build") as? String,
            platform = m?.get("platform") as? String,
            deviceType = m?.get("device_type") as? String,
            locale = m?.get("locale") as? String,
            timezone = m?.get("timezone") as? String,
            osVersion = m?.get("os_version") as? String,
            deviceModel = m?.get("device_model") as? String,
            sessionId = m?.get("session_id") as? String,
            sdk = m?.get("sdk") as? String,
        )
    }
}

/** `identity` of an event: only on `$identify` and `$set`. */
internal data class Identity(val billingId: String? = null, val traits: Map<String, Any?>? = null) {
    fun toJson(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        billingId?.let { put("billing_id", it) }
        traits?.let { put("traits", it) }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromJson(m: Map<*, *>?): Identity? = m?.let {
            Identity(it["billing_id"] as? String, it["traits"] as? Map<String, Any?>)
        }
    }
}

/** One event as it sits in the queue and goes over the wire. */
internal data class Event(
    val uuid: String,
    val event: String,
    val ts: String,
    val anonId: String,
    val userId: String?,
    val properties: Map<String, Any?>,
    val context: EventContext,
    val identity: Identity?,
) {
    fun toJson(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        put("uuid", uuid)
        put("event", event)
        put("ts", ts)
        put("anon_id", anonId)
        // `user_id` is always present: the id, or null before identify().
        put("user_id", userId)
        put("properties", properties)
        put("context", context.toJson())
        identity?.let { put("identity", it.toJson()) }
    }

    companion object {
        /** An event read back from the queue file, or null when it's missing a required field. */
        @Suppress("UNCHECKED_CAST")
        fun fromJson(m: Map<*, *>): Event? {
            val uuid = m["uuid"] as? String ?: return null
            val event = m["event"] as? String ?: return null
            val ts = m["ts"] as? String ?: return null
            val anonId = m["anon_id"] as? String ?: return null
            return Event(
                uuid = uuid,
                event = event,
                ts = ts,
                anonId = anonId,
                userId = m["user_id"] as? String,
                properties = (m["properties"] as? Map<String, Any?>) ?: emptyMap(),
                context = EventContext.fromJson(m["context"] as? Map<*, *>),
                identity = Identity.fromJson(m["identity"] as? Map<*, *>),
            )
        }
    }
}

/** Body of `POST /v1/batch`. */
internal object BatchBody {
    fun encode(key: String, sentAt: String, batch: List<Event>): String = Json.encode(
        linkedMapOf(
            "key" to key,
            "sent_at" to sentAt,
            "batch" to batch.map { it.toJson() },
        ),
    )
}
