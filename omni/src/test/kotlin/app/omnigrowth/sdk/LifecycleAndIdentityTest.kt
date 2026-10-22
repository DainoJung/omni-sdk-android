package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleAndIdentityTest {
    private fun props(e: Map<String, Any?>) = e["properties"] as Map<*, *>
    private fun session(e: Map<String, Any?>) = (e["context"] as Map<*, *>)["session_id"]

    @Test
    fun firstLaunchSendsInstalledThenOpenedOnceInTheForeground() {
        val h = Harness(permission = null)
        h.client.start()
        h.worker.drain()
        assertEquals("the process can start without UI: no open yet", listOf("\$app_installed"), h.names())
        h.client.appDidBecomeActive()
        h.client.appDidBecomeActive() // a second ON_START without a background in between isn't an open
        val events = h.events()
        assertEquals(listOf("\$app_installed", "\$app_opened"), events.map { it["event"] })
        assertEquals(mapOf("version" to "1.4.0", "build" to "52"), props(events[0]))
        assertEquals(mapOf("from_background" to false), props(events[1]))
        assertNotNull(h.storage.long(StoreKey.FIRST_LAUNCH_AT))
    }

    @Test
    fun relaunchOfTheSameVersionSendsNoInstallOrUpdate() {
        val first = Harness(permission = null).launch()
        val second = Harness(storage = first.storage, permission = null).launch()
        assertEquals(listOf("\$app_opened"), second.names())
    }

    @Test
    fun newVersionSendsUpdated() {
        val first = Harness(permission = null).launch()
        val second = Harness(device = testDevice.copy(appVersion = "1.5.0", build = "60"), storage = first.storage, permission = null).launch()
        val events = second.events()
        assertEquals("\$app_updated", events[0]["event"])
        assertEquals(
            mapOf("version" to "1.5.0", "build" to "60", "previous_version" to "1.4.0", "previous_build" to "52"),
            props(events[0]),
        )
    }

    @Test
    fun anUpdateToTheFirstVersionWithOmniIsNotAnInstall() {
        val h = Harness(device = testDevice.copy(isFreshInstall = false), permission = null).launch()
        val events = h.events()
        assertEquals("\$app_updated", events[0]["event"])
        assertEquals("", props(events[0])["previous_version"])
        assertNull("counts as an old user for new-user experiments", h.storage.long(StoreKey.FIRST_LAUNCH_AT))
    }

    @Test
    fun comingBackFromTheBackgroundIsAnOpenAndKeepsTheSessionUnder30Minutes() {
        val h = Harness(permission = null).launch()
        h.client.appDidEnterBackground()
        h.worker.drain()
        h.clock.now += 29 * 60_000L
        h.client.appDidBecomeActive()
        val events = h.events()
        val opens = events.filter { it["event"] == "\$app_opened" }
        assertEquals(listOf(false, true), opens.map { props(it)["from_background"] })
        assertEquals(session(opens[0]), session(opens[1]))
    }

    @Test
    fun aNewSessionStartsAfter30MinutesInTheBackground() {
        val h = Harness(permission = null).launch()
        h.client.appDidEnterBackground()
        h.worker.drain()
        h.clock.now += 30 * 60_000L
        h.client.appDidBecomeActive()
        val opens = h.events().filter { it["event"] == "\$app_opened" }
        assertNotEquals(session(opens[0]), session(opens[1]))
    }

    @Test
    fun aNewSessionStartsAfter30MinutesWithoutEvents() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null)
        h.client.track("a", null)
        h.worker.drain()
        h.clock.now += 31 * 60_000L
        h.client.track("b", null)
        val events = h.events()
        assertNotEquals(session(events[0]), session(events[1]))
    }

    @Test
    fun captureLifecycleOffSendsNoLifecycleEvents() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null).launch()
        h.client.appDidEnterBackground()
        h.client.appDidBecomeActive()
        assertEquals(emptyList<String>(), h.names())
    }

    @Test
    fun identifyLinksTheUserAndCarriesTraits() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null)
        val anon = h.client.anonymousId
        h.client.track("before", null)
        h.client.identify("  user_1 ", mapOf("plan" to "pro"))
        h.client.track("after", null)
        val events = h.events()
        assertNull(events[0]["user_id"])
        assertEquals("\$identify", events[1]["event"])
        assertEquals("user_1", events[1]["user_id"])
        assertEquals(anon, events[1]["anon_id"])
        assertEquals(mapOf("traits" to mapOf("plan" to "pro")), events[1]["identity"])
        assertEquals("user_1", events[2]["user_id"])
        assertEquals("user_1", h.storage.string(StoreKey.USER_ID))
    }

    @Test
    fun setTraitsEmailAndBillingIdSendSet() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null)
        h.client.setTraits(mapOf("plan" to "free"))
        h.client.setEmail("a@b.co", consent = true)
        h.client.setBillingId(" cus_123 ")
        h.client.setBillingId(" ")
        h.client.setTraits(emptyMap())
        val events = h.events()
        assertEquals(listOf("\$set", "\$set", "\$set"), events.map { it["event"] })
        assertEquals(mapOf("traits" to mapOf("plan" to "free")), events[0]["identity"])
        assertEquals(mapOf("traits" to mapOf("email" to "a@b.co", "email_consent" to true)), events[1]["identity"])
        assertEquals(mapOf("billing_id" to "cus_123"), events[2]["identity"])
    }

    @Test
    fun resetGivesANewAnonIdAndClearsTheUser() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null)
        val anon = h.client.anonymousId
        h.client.identify("user_1", null)
        h.client.reset()
        h.client.track("after", null)
        val events = h.events()
        val after = events.last()
        assertNull(after["user_id"])
        assertNotEquals(anon, after["anon_id"])
        assertEquals(h.client.anonymousId, h.storage.string(StoreKey.ANON_ID))
        assertNull(h.storage.string(StoreKey.USER_ID))
        assertNotEquals("a new session after reset", session(events[0]), session(after))
    }

    @Test
    fun deleteUserSendsTheCurrentIdsRightAwayThenResets() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null)
        val anon = h.client.anonymousId
        h.client.identify("user_1", null)
        h.client.deleteUser()
        h.worker.drain()
        val deleted = h.transport.sentEvents.single { it["event"] == "\$delete_user" }
        assertEquals(anon, deleted["anon_id"])
        assertEquals("user_1", deleted["user_id"])
        assertNotEquals(anon, h.client.anonymousId)
        assertNull(h.client.currentUserId)
    }

    @Test
    fun idsPersistAcrossLaunches() {
        val first = Harness(permission = null)
        first.client.identify("user_1", null)
        val second = Harness(storage = first.storage, permission = null)
        assertEquals(first.client.anonymousId, second.client.anonymousId)
        assertEquals("user_1", second.client.currentUserId)
        assertTrue(second.storage.string(StoreKey.BUCKET_ID)!!.isNotEmpty())
        assertFalse(second.client.isOptedOut)
    }
}
