package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md "Push buttons": the capability flag, the config's categories, reading a push and its opens. */
class PushButtonsTest {
    private fun harness(showsPushes: Boolean, storage: MemoryStore = MemoryStore()) =
        Harness(options = Omni.Options(captureLifecycle = false, showsPushes = showsPushes), storage = storage, permission = null)

    private fun tokens(h: Harness) = h.events().filter { it["event"] == "\$push_token" }.map { it["properties"] }

    // $push_token

    @Test
    fun pushTokenSaysButtonsOnlyWhenTheAppShowsPushes() {
        val on = harness(showsPushes = true)
        on.client.setPushToken("fcm_1")
        assertEquals(
            listOf(mapOf("token" to "fcm_1", "provider" to "fcm", "environment" to "production", "buttons" to true)),
            tokens(on),
        )
        val off = harness(showsPushes = false)
        off.client.setPushToken("fcm_1")
        assertFalse((tokens(off).single() as Map<*, *>).containsKey("buttons"))
    }

    @Test
    fun turningShowsPushesOnResendsTheTokenAtOnce() {
        val store = MemoryStore()
        val before = harness(showsPushes = false, storage = store)
        before.client.setPushToken("fcm_1")
        before.worker.drain()

        // An app update turns it on: Omni hears about the buttons now, not when the weekly resend comes around.
        val after = harness(showsPushes = true, storage = store)
        after.client.setPushToken("fcm_1")
        after.client.setPushToken("fcm_1")
        assertEquals(listOf(true), tokens(after).map { (it as Map<*, *>)["buttons"] })

        // …and turning it off again says so too.
        val again = harness(showsPushes = false, storage = store)
        again.client.setPushToken("fcm_1")
        assertEquals(1, tokens(again).size)
        assertFalse((tokens(again).single() as Map<*, *>).containsKey("buttons"))
    }

    // Opens

    @Test
    fun opensCarryTheButtonAndItsLink() {
        val button = Push.opened { mapOf("omni_message_id" to "m_1", "omni_action" to "b1", "omni_url" to "scribo://review")[it] }!!
        assertEquals("b1", button.action)
        assertEquals("scribo://review", button.url)
        assertEquals("open", Push.opened { mapOf("omni_message_id" to "m_1", "omni_action" to " ")[it] }!!.action)
        assertNull(Push.opened { mapOf("omni_action" to "b1")[it] })
    }

    // Reading a push (data → notification)

    private val full = mapOf(
        "omni_message_id" to "m_42",
        "omni_title" to "Your streak",
        "omni_subtitle" to "Day 12",
        "omni_body" to "Five words are due for review.",
        "omni_url" to "scribo://home",
        "omni_image" to "https://cdn.test/streak.png",
        "omni_channel" to "reminders",
        "omni_group" to "streaks",
        "omni_actions" to """[{"id":"b1","title":"Review now","url":"scribo://review"},{"id":"b2","title":"Later"}]""",
    )

    @Test
    fun aFullPushMapsToItsNotification() {
        val p = PushContent.from(full)!!
        assertEquals("m_42", p.messageId)
        assertEquals("Your streak", p.title)
        assertEquals("Day 12\nFive words are due for review.", p.body)
        assertEquals("scribo://home", p.url)
        assertEquals("https://cdn.test/streak.png", p.imageUrl)
        assertEquals("reminders", p.channel)
        assertEquals("streaks", p.group)
        assertEquals(listOf("b1", "b2"), p.actions.map { it.id })
        assertEquals(listOf("Review now", "Later"), p.actions.map { it.title })
        assertEquals("m_42".hashCode(), p.notificationId)
    }

    @Test
    fun theIntentsCarryWhatTheOpenIsTrackedBy() {
        val p = PushContent.from(full)!!
        assertEquals(
            mapOf("omni_message_id" to "m_42", "omni_action" to "open", "omni_url" to "scribo://home"),
            p.openExtras(null),
        )
        assertEquals(
            mapOf("omni_message_id" to "m_42", "omni_action" to "b1", "omni_url" to "scribo://review"),
            p.openExtras(p.actions[0]),
        )
        // A button without a link opens what the notification opens.
        assertEquals("scribo://home", p.openExtras(p.actions[1])["omni_url"])
        // Read back the way an opened intent is.
        val opened = Push.opened { p.openExtras(p.actions[0])[it] }!!
        assertEquals("b1", opened.action)

        val codes = listOf(p.requestCode(null)) + p.actions.map { p.requestCode(it) }
        assertEquals(3, codes.toSet().size)
        assertTrue(PushContent.from(full + ("omni_message_id" to "m_43"))!!.requestCode(null) != p.requestCode(null))
    }

    @Test
    fun aMinimalPushAndWhatIsLeftOut() {
        val p = PushContent.from(mapOf("omni_message_id" to "m_1", "omni_body" to "Hi"))!!
        assertNull(p.title)
        assertEquals("Hi", p.body)
        assertNull(p.url)
        assertNull(p.imageUrl)
        assertNull(p.channel)
        assertNull(p.group)
        assertTrue(p.actions.isEmpty())
        assertEquals(mapOf("omni_message_id" to "m_1", "omni_action" to "open"), p.openExtras(null))

        assertEquals("Only a subtitle", PushContent.from(mapOf("omni_message_id" to "m", "omni_subtitle" to "Only a subtitle"))!!.body)
        assertNull("not an http(s) image", PushContent.from(mapOf("omni_message_id" to "m", "omni_image" to "file:///x.png"))!!.imageUrl)
        assertNull("blank values are absent", PushContent.from(mapOf("omni_message_id" to "m", "omni_title" to " "))!!.title)
    }

    @Test
    fun notOmnisPushIsLeftToTheApp() {
        assertNull(PushContent.from(mapOf("title" to "Sale!", "body" to "50% off")))
        assertNull(PushContent.from(mapOf("omni_message_id" to " ")))
        assertNull(PushContent.from(emptyMap()))
    }

    @Test
    fun buttonsAreReadLenientlyAndAtMostThree() {
        fun ids(json: String) = PushContent.from(mapOf("omni_message_id" to "m", "omni_actions" to json))!!.actions.map { it.id }
        assertEquals(
            listOf("a", "c", "d"),
            ids(
                """[{"id":"a","title":"A"},{"id":"","title":"x"},{"title":"no id"},{"id":"b"},"text",""" +
                    """{"id":"a","title":"again"},{"id":"open","title":"reserved"},{"id":"c","title":"C"},""" +
                    """{"id":"d","title":"D"},{"id":"e","title":"E"}]""",
            ),
        )
        assertEquals(emptyList<String>(), ids("not json"))
        assertEquals(emptyList<String>(), ids("""{"id":"a","title":"A"}"""))
    }

    // push_categories in the config

    private val configWithCategories = """{"experiments":[],"push_categories":[
        {"id":"omni_7f3a2c","actions":[{"id":"b1","title":"Review now","url":"scribo://review","foreground":true},
                                        {"id":"b2","title":"Later","foreground":false}]},
        {"actions":[]}, "x", {"id":"omni_empty"}],"ttl":300}"""

    @Test
    fun categoriesAreParsedLeniently() {
        val c = ExperimentConfig.parse(configWithCategories)!!
        assertEquals(listOf("omni_7f3a2c", "omni_empty"), c.pushCategories.map { it.id })
        val actions = c.pushCategories[0].actions
        assertEquals(listOf("b1", "b2"), actions.map { it.id })
        assertEquals(listOf("scribo://review", null), actions.map { it.url })
        assertEquals(listOf(true, false), actions.map { it.foreground })
        assertTrue(c.pushCategories[1].actions.isEmpty())

        assertTrue(ExperimentConfig.parse("""{"experiments":[],"ttl":300}""")!!.pushCategories.isEmpty())
        val odd = ExperimentConfig.parse("""{"experiments":[],"push_categories":{"id":"x"}}""")!!
        assertTrue("a shape it can't read isn't the whole config", odd.pushCategories.isEmpty())
    }

    @Test
    fun categoriesAreKeptWithTheConfigCopy() {
        val store = MemoryStore()
        val copy = MemoryText()
        val first = Harness(options = Omni.Options(captureLifecycle = false), storage = store, configStore = copy, permission = null)
        first.transport.configBody = configWithCategories
        first.launch()
        assertEquals(listOf("omni_7f3a2c", "omni_empty"), first.client.pushCategories().map { it.id })

        // Next launch, before (or without) a fetch: the device's copy.
        val second = Harness(options = Omni.Options(captureLifecycle = false), storage = store, configStore = copy, permission = null)
        second.transport.respond = { null }
        assertEquals(listOf("omni_7f3a2c", "omni_empty"), second.client.pushCategories().map { it.id })
    }

    @Test
    fun largeImagesAreDecodedSmaller() {
        assertEquals(1, PushNotifier.sampleSize(1024, 512))
        assertEquals(1, PushNotifier.sampleSize(1440, 1440))
        assertEquals(2, PushNotifier.sampleSize(2000, 1000))
        assertEquals(4, PushNotifier.sampleSize(3000, 4000))
    }
}
