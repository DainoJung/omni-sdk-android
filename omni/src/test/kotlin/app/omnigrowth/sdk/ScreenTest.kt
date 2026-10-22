package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTest {
    @Test
    fun namesComeFromTheActivityClass() {
        assertEquals("WordList", ScreenName.from("com.example.words.WordListActivity"))
        assertEquals("Main", ScreenName.from("com.example.MainActivity"))
        assertEquals("Paywall", ScreenName.from("com.example.ui.PaywallFragment"))
        assertEquals("Settings", ScreenName.from("com.example.Settings"))
        assertEquals("Detail", ScreenName.from("com.example.Outer\$DetailActivity"))
        assertEquals("Activity", ScreenName.from("com.example.Activity"))
        assertEquals("ActivityLog", ScreenName.from("com.example.ActivityLogActivity"))
    }

    @Test
    fun systemLibraryAndAnonymousActivitiesAreSkipped() {
        assertNull(ScreenName.from("com.android.billingclient.api.ProxyBillingActivity"))
        assertNull(ScreenName.from("com.google.android.gms.auth.api.signin.internal.SignInHubActivity"))
        assertNull(ScreenName.from("androidx.activity.ComponentActivity"))
        assertNull(ScreenName.from("android.app.Activity"))
        assertNull(ScreenName.from("com.example.Main\$1"))
        assertNull(ScreenName.from("com.example._Generated"))
        assertNull(ScreenName.from(""))
    }

    @Test
    fun dedupeSkipsTheSameScreenTwiceInARow() {
        val d = ScreenDeduper()
        assertEquals(listOf(true, false, true, true, false), listOf("A", "A", "B", "A", "A").map(d::shouldSend))
        d.record("B")
        assertEquals(false, d.shouldSend("B"))
    }

    @Test
    fun automaticScreensCarryAutoAndManualOnesDoNot() {
        val h = Harness(options = Omni.Options(captureLifecycle = false), permission = null)
        h.client.observedScreen("Home", auto = true)
        h.client.observedScreen("Home", auto = true) // resumed again after a dialog
        h.client.screen("Paywall", mapOf("placement" to "onboarding"))
        h.client.screen("Paywall", null) // explicit calls always count
        h.client.observedScreen("Paywall", auto = true) // the same screen as just before
        h.client.observedScreen("Home", auto = true)
        val events = h.events()
        assertEquals(listOf("\$screen"), events.map { it["event"] }.distinct())
        assertEquals(
            listOf(
                mapOf("name" to "Home", "auto" to true),
                mapOf("name" to "Paywall", "placement" to "onboarding"),
                mapOf("name" to "Paywall"),
                mapOf("name" to "Home", "auto" to true),
            ),
            events.map { it["properties"] },
        )
    }

    @Test
    fun captureScreensOffSkipsAutomaticOnes() {
        val h = Harness(options = Omni.Options(captureLifecycle = false, captureScreens = false), permission = null)
        h.client.observedScreen("Home", auto = true)
        h.client.screen("Paywall", null)
        assertEquals(listOf("\$screen"), h.names())
        assertEquals("Paywall", (h.events().single()["properties"] as Map<*, *>)["name"])
    }
}
