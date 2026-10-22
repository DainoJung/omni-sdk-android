package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PushAndAttributionTest {
    private fun quiet(storage: MemoryStore = MemoryStore(), permission: String? = null) =
        Harness(options = Omni.Options(captureLifecycle = false), storage = storage, permission = permission)

    private fun props(h: Harness, event: String) = h.events().filter { it["event"] == event }.map { it["properties"] }

    @Test
    fun tokenPolicy() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(Push.shouldSendToken("t", null, null, 0))
        assertFalse(Push.shouldSendToken("t", "t", 0, 6 * day))
        assertTrue(Push.shouldSendToken("t", "t", 0, 7 * day))
        assertTrue(Push.shouldSendToken("t2", "t", 0, day))
        assertTrue("clock went backwards", Push.shouldSendToken("t", "t", 5 * day, day))
    }

    @Test
    fun pushTokenIsSentWhenNewChangedOrAWeekOld() {
        val h = quiet()
        h.client.setPushToken("fcm_1")
        h.client.setPushToken("fcm_1")
        h.worker.drain()
        h.clock.now += 3 * 24 * 3600_000L
        h.client.setPushToken("fcm_1")
        h.client.setPushToken("fcm_2")
        h.worker.drain()
        h.clock.now += 7 * 24 * 3600_000L
        h.client.setPushToken("fcm_2")
        h.client.setPushToken(" ")
        assertEquals(
            listOf("fcm_1", "fcm_2", "fcm_2").map { mapOf("token" to it, "provider" to "fcm", "environment" to "production") },
            props(h, "\$push_token"),
        )
    }

    @Test
    fun aKnownTokenIsResentWeeklyOnForegroundAndAfterReset() {
        val store = MemoryStore()
        val first = quiet(store)
        first.client.setPushToken("fcm_1")
        first.worker.drain()
        val second = Harness(options = Omni.Options(), storage = store, clock = FakeClock(first.clock.now + 8 * 24 * 3600_000L), permission = null)
        second.launch()
        assertEquals(1, props(second, "\$push_token").size)
        second.client.reset()
        assertEquals(2, props(second, "\$push_token").size)
    }

    @Test
    fun permissionIsSentAtStartAndWhenItChanges() {
        val h = Harness(options = Omni.Options(), permission = "authorized").launch()
        h.client.appDidEnterBackground()
        h.client.appDidBecomeActive()
        h.worker.drain()
        h.permission = "denied"
        h.client.appDidEnterBackground()
        h.client.appDidBecomeActive()
        h.worker.drain()
        assertEquals(listOf(mapOf("status" to "authorized"), mapOf("status" to "denied")), props(h, "\$push_permission"))
    }

    @Test
    fun pushOpenedOncePerMessage() {
        val h = quiet()
        h.client.pushOpened("m_1")
        h.client.pushOpened("m_1") // the automatic intent check and the app's own call for the same push
        h.client.pushOpened("m_2")
        assertEquals(listOf(mapOf("message_id" to "m_1"), mapOf("message_id" to "m_2")), props(h, "\$push_opened"))
    }

    @Test
    fun readsOmniPushData() {
        val data = mapOf("omni_message_id" to " m_1 ", "omni_url" to "scribo://paywall")
        val opened = Push.opened { data[it] }!!
        assertEquals("m_1", opened.messageId)
        assertEquals("scribo://paywall", opened.url)
        assertNull(Push.opened { mapOf("google.message_id" to "x")[it] })
        assertNull(Push.opened { mapOf("omni_message_id" to "m", "omni_url" to "")[it] }!!.url)
    }

    @Test
    fun parsesThePlayReferrer() {
        assertEquals(
            mapOf("utm_source" to "instagram", "utm_medium" to "social post", "utm_campaign" to "ct_42", "x" to ""),
            Attribution.parse("utm_source=instagram&utm_medium=social+post&utm_campaign=ct_42&x&utm_source=again"),
        )
        assertEquals(mapOf("utm_source" to "%zz"), Attribution.parse("utm_source=%zz"))
        assertEquals(emptyMap<String, String>(), Attribution.parse(""))
    }

    @Test
    fun attributionPropertiesFollowTheProtocol() {
        val found = Attribution.Result.Found(
            "utm_source=google-play&utm_medium=organic&utm_campaign=ct_9",
            clickTsSeconds = 1_792_573_198, installTsSeconds = 0,
        )
        val p = Attribution.properties(found)
        assertEquals(
            listOf("source", "referrer", "utm_source", "utm_medium", "utm_campaign", "click_ts"),
            p.keys.toList(),
        )
        assertEquals("play_install_referrer", p["source"])
        assertEquals("google-play", p["utm_source"])
        assertEquals(Timestamp.format(1_792_573_198_000L), p["click_ts"])
    }

    @Test
    fun attributionIsSentOnceAndGivesUpAfterThreeTries() {
        val h = quiet()
        assertTrue(h.client.needsAttribution)
        h.client.recordAttribution(Attribution.Result.Found("utm_source=tiktok", 0, 0))
        assertEquals(listOf(mapOf("source" to "play_install_referrer", "referrer" to "utm_source=tiktok", "utm_source" to "tiktok")),
            props(h, "\$attribution"))
        assertFalse(h.client.needsAttribution)

        val noStore = quiet()
        noStore.client.recordAttribution(Attribution.Result.NotAvailable)
        noStore.worker.drain()
        assertFalse(noStore.client.needsAttribution)

        val flaky = quiet()
        repeat(2) { flaky.client.recordAttribution(Attribution.Result.TryLater) }
        flaky.worker.drain()
        assertTrue(flaky.client.needsAttribution)
        flaky.client.recordAttribution(Attribution.Result.TryLater)
        flaky.worker.drain()
        assertFalse(flaky.client.needsAttribution)
        assertTrue(props(flaky, "\$attribution").isEmpty())
    }

    @Test
    fun deviceTypeAndLocale() {
        assertEquals("phone", DeviceInfo.deviceType(411))
        assertEquals("tablet", DeviceInfo.deviceType(600))
        assertEquals("ko-KR", DeviceInfo.localeTag(Locale.KOREA))
        assertEquals("zh-Hans-CN", DeviceInfo.localeTag(Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("ja-JP", DeviceInfo.localeTag(Locale.forLanguageTag("ja-JP-u-ca-japanese")))
        assertNull(DeviceInfo.localeTag(Locale.ROOT))
    }
}
