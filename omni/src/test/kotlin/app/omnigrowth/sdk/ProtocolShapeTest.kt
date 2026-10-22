package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire format, field by field, against PROTOCOL.md's `POST /v1/batch`. */
class ProtocolShapeTest {
    private val contextKeys = listOf(
        "app_version", "build", "platform", "device_type", "locale", "os_version", "device_model", "session_id", "sdk",
    )

    @Test
    fun batchOfTwoEventsMatchesTheProtocolExactly() {
        val context = EventContext(
            appVersion = "1.4.0", build = "52", platform = "android", deviceType = "phone", locale = "ko-KR",
            osVersion = "16", deviceModel = "Pixel 9", sessionId = "0192f6c3-0000-4000-8000-000000000000",
            sdk = "omni-android/0.1.0",
        )
        val events = listOf(
            Event("0192f6c4-0000-4000-8000-000000000001", "lesson_completed", "2026-10-21T08:59:58.120Z", "a1b2", null,
                mapOf("lesson" to 3L), context, null),
            Event("0192f6c4-0000-4000-8000-000000000002", "\$identify", "2026-10-21T08:59:59.000Z", "a1b2", "u_1",
                emptyMap(), context, Identity("rc_1", mapOf("email" to "a@b.co", "email_consent" to true))),
        )
        val body = BatchBody.encode("opk_test", "2026-10-21T09:00:00.000Z", events)
        val root = Json.parseObject(body)!!

        assertEquals(listOf("key", "sent_at", "batch"), root.keys.toList())
        assertEquals("opk_test", root["key"])
        assertEquals("2026-10-21T09:00:00.000Z", root["sent_at"])
        val batch = root["batch"] as List<*>
        assertEquals(2, batch.size)

        val first = batch[0] as Map<*, *>
        assertEquals(listOf("uuid", "event", "ts", "anon_id", "user_id", "properties", "context"), first.keys.toList())
        assertTrue("user_id is present as null", first.containsKey("user_id") && first["user_id"] == null)
        assertEquals(mapOf("lesson" to 3L), first["properties"])
        assertEquals(contextKeys, (first["context"] as Map<*, *>).keys.toList())

        val second = batch[1] as Map<*, *>
        assertEquals(
            listOf("uuid", "event", "ts", "anon_id", "user_id", "properties", "context", "identity"),
            second.keys.toList(),
        )
        val identity = second["identity"] as Map<*, *>
        assertEquals(listOf("billing_id", "traits"), identity.keys.toList())
        assertEquals(mapOf("email" to "a@b.co", "email_consent" to true), identity["traits"])

        // Literally, so a renamed field can't hide behind the parser.
        assertTrue(body.contains("\"sent_at\":\"2026-10-21T09:00:00.000Z\""))
        assertTrue(body.contains("\"user_id\":null"))
        assertTrue(body.contains("\"sdk\":\"omni-android/0.1.0\""))
    }

    @Test
    fun clientSendsWhatItTracksInThatShape() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null)
        h.client.start(appInForeground = true)
        h.client.track("lesson_completed", mapOf("lesson" to 3))
        h.client.track("share", mapOf("content" to "deck", "channel" to "instagram"))
        h.flush()

        assertEquals(1, h.transport.batches.size)
        val request = h.transport.requests.single { it.method == "POST" }
        assertEquals("https://api.omnigrowth.app/v1/batch", request.url)
        assertEquals("application/json", request.headers["Content-Type"])
        val events = h.transport.sentEvents
        assertEquals(listOf("lesson_completed", "share"), events.map { it["event"] })
        for (e in events) {
            assertEquals(listOf("uuid", "event", "ts", "anon_id", "user_id", "properties", "context"), e.keys.toList())
            val ctx = e["context"] as Map<*, *>
            assertEquals(contextKeys, ctx.keys.toList())
            assertEquals("android", ctx["platform"])
            assertEquals("omni-android/${Omni.sdkVersion}", ctx["sdk"])
            assertTrue((e["ts"] as String).matches(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z""")))
        }
        assertEquals(events[0]["context"].let { (it as Map<*, *>)["session_id"] }, (events[1]["context"] as Map<*, *>)["session_id"])
        assertFalse(events[0]["uuid"] == events[1]["uuid"])
    }

    @Test
    fun contextLeavesOutWhatIsUnknown() {
        val ctx = EventContext(platform = "android").toJson()
        assertEquals(listOf("platform"), ctx.keys.toList())
        assertNull(Identity().toJson()["billing_id"])
    }

    @Test
    fun queuedEventsRoundTripThroughTheFile() {
        val e = Event("u", "x", "2026-10-21T08:59:58.120Z", "a", "user", mapOf("n" to 1.5, "list" to listOf(1L, "b")),
            EventContext(platform = "android", sessionId = "s"), Identity(billingId = "cus_1"))
        assertEquals(e, Event.fromJson(Json.parseObject(Json.encode(e.toJson()))!!))
    }
}
