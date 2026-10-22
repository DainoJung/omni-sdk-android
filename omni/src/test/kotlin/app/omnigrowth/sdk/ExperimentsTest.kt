package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentsTest {
    @Test
    fun matchesTheSharedTestVectors() {
        val text = javaClass.classLoader!!.getResource("test-vectors.json")!!.readText()
        val vectors = (Json.parseObject(text)!!["fnv1a32"] as List<*>).map { it as Map<*, *> }
        assertEquals(10, vectors.size)
        for (v in vectors) {
            val input = v["input"] as String
            assertEquals(input, v["fnv1a32"], Fnv1a.hash32(input))
            val (exp, bucketId) = input.split(":", limit = 2)
            val bucket = Assignment.bucket(exp, bucketId)
            assertEquals(input, (v["bucket"] as Long).toInt(), bucket)
            assertEquals(input, (v["variant_index_50_50"] as Long).toInt(), Assignment.pick(listOf(50.0, 50.0), bucket))
            assertEquals(input, (v["variant_index_10_30_60"] as Long).toInt(), Assignment.pick(listOf(10.0, 30.0, 60.0), bucket))
        }
    }

    @Test
    fun pickWalksRunningTotals() {
        assertEquals(0, Assignment.pick(listOf(1.0, 1.0), 0))
        assertEquals(0, Assignment.pick(listOf(1.0, 1.0), 4999))
        assertEquals(1, Assignment.pick(listOf(1.0, 1.0), 5000))
        assertEquals(1, Assignment.pick(listOf(1.0, 1.0), 9999))
        assertEquals(2, Assignment.pick(listOf(1.0, 1.0, 1.0), 9999))
        assertNull(Assignment.pick(emptyList(), 10))
        assertNull(Assignment.pick(listOf(0.0, 0.0), 10))
    }

    private val bucketId = "00000000-0000-0000-0000-000000000000"

    private fun config(vararg experiments: String, ttl: Int = 300) =
        """{"experiments":[${experiments.joinToString(",")}],"ttl":$ttl}"""

    private fun exp(
        key: String = "paywall_headline",
        audience: String = "{}",
        winner: String? = null,
    ) = """{"key":"$key","variants":[{"key":"control","weight":50,"payload":{}},""" +
        """{"key":"b","weight":50,"payload":{"headline":"Remember every word you meet"}}],""" +
        """"audience":$audience,"winner":${winner?.let { "\"$it\"" } ?: "null"}}"""

    private fun harness(configBody: String, storage: MemoryStore = MemoryStore(), configStore: MemoryText = MemoryText()): Harness {
        storage.set(StoreKey.BUCKET_ID, bucketId)
        val h = Harness(options = Omni.Options(captureLifecycle = false), storage = storage, configStore = configStore, permission = null)
        h.transport.configBody = configBody
        return h
    }

    @Test
    fun assignsOnTheDeviceAndSendsExposureOncePerSession() {
        val h = harness(config(exp())).launch()
        // bucket 8400 for this experiment and bucket id → the second variant (test vectors).
        assertEquals("b", h.client.variant("paywall_headline"))
        assertEquals(mapOf("headline" to "Remember every word you meet"), h.client.payload("paywall_headline"))
        assertEquals("b", h.client.variant("paywall_headline"))
        assertNull(h.client.variant("not_listed"))
        val exposures = h.events().filter { it["event"] == "\$exposure" }
        assertEquals(1, exposures.size)
        assertEquals(mapOf("experiment" to "paywall_headline", "variant" to "b"), exposures[0]["properties"])

        // A new session (30 min without events) → exposed again.
        h.clock.now += 31 * 60_000L
        h.client.variant("paywall_headline")
        h.client.variant("paywall_headline")
        assertEquals(2, h.events().count { it["event"] == "\$exposure" })
    }

    @Test
    fun nothingBeforeTheConfigIsLoaded() {
        val h = harness(config(exp()))
        assertNull(h.client.variant("paywall_headline"))
        assertNull(h.client.payload("paywall_headline"))
    }

    @Test
    fun platformsMustIncludeAndroid() {
        val h = harness(config(exp(audience = """{"platforms":["ios","ipados"]}"""))).launch()
        assertNull(h.client.variant("paywall_headline"))
        val ok = harness(config(exp(audience = """{"platforms":["android"]}"""))).launch()
        assertEquals("b", ok.client.variant("paywall_headline"))
    }

    @Test
    fun newUsersSinceComparesTheFirstLaunch() {
        val store = MemoryStore()
        val h = harness(config(exp(audience = """{"new_users_since":"2026-10-21T00:00:00.000Z"}""")), store).launch()
        assertEquals("first launch is now (2026-10-21T09:00Z), after the start", "b", h.client.variant("paywall_headline"))

        val later = harness(config(exp(audience = """{"new_users_since":"2026-10-22T00:00:00.000Z"}"""))).launch()
        assertNull(later.client.variant("paywall_headline"))

        // Ran the app before the SDK kept the first launch: an old user.
        val old = MemoryStore().apply {
            set(StoreKey.LAST_VERSION, "1.0")
            set(StoreKey.LAST_BUILD, "1")
        }
        val oldUser = harness(config(exp(audience = """{"new_users_since":"2020-01-01T00:00:00.000Z"}""")), old).launch()
        assertNull(oldUser.client.variant("paywall_headline"))

        val unreadable = harness(config(exp(audience = """{"new_users_since":"soon"}"""))).launch()
        assertNull(unreadable.client.variant("paywall_headline"))
    }

    @Test
    fun aWinnerGoesToEveryMatchingDeviceWithoutExposures() {
        val h = harness(config(exp(audience = """{"platforms":["android"],"new_users_since":"2030-01-01T00:00:00Z"}""", winner = "control"))).launch()
        assertEquals("control", h.client.variant("paywall_headline"))
        assertEquals(emptyMap<String, Any?>(), h.client.payload("paywall_headline"))
        assertFalse(h.events().any { it["event"] == "\$exposure" })

        val ios = harness(config(exp(audience = """{"platforms":["ios"]}""", winner = "b"))).launch()
        assertNull(ios.client.variant("paywall_headline"))
    }

    @Test
    fun theBucketIdSurvivesResetSoTheVariantStays() {
        val h = harness(config(exp())).launch()
        val before = h.client.variant("paywall_headline")
        h.client.reset()
        h.worker.drain()
        assertEquals(before, h.client.variant("paywall_headline"))
        assertEquals(bucketId, h.storage.string(StoreKey.BUCKET_ID))
        val exposures = h.events().filter { it["event"] == "\$exposure" }
        assertEquals("the new anonymous person is exposed too", 2, exposures.size)
        assertFalse(h.transport.requests.any { String(it.body ?: ByteArray(0)).contains(bucketId) })
    }

    @Test
    fun theConfigIsKeptOnDiskAndReadAtOnceNextLaunch() {
        val disk = MemoryText()
        val first = harness(config(exp()), configStore = disk).launch()
        assertTrue(disk.text!!.contains("paywall_headline"))

        val second = harness(config(), configStore = disk) // not started: nothing fetched yet
        assertEquals("b", second.client.variant("paywall_headline"))
        assertEquals(0, second.transport.configFetches)
        first.worker.drain()
    }

    @Test
    fun aFailedFetchKeepsTheLastCopy() {
        val disk = MemoryText()
        harness(config(exp()), configStore = disk).launch()
        val h = harness(config(exp()), configStore = disk)
        h.transport.respond = { req -> if (req.method == "GET") HttpResponse(500) else HttpResponse(200) }
        h.launch()
        assertEquals("b", h.client.variant("paywall_headline"))
        h.transport.respond = { req -> if (req.method == "GET") HttpResponse(200, "not json") else HttpResponse(200) }
        h.client.loadExperiments(2000) {}
        h.worker.drain()
        assertEquals("b", h.client.variant("paywall_headline"))
    }

    @Test
    fun loadExperimentsResolvesWhenFetchedOrAfterTheTimeout() {
        val h = harness(config(exp()))
        val results = ArrayList<Boolean>()
        h.client.loadExperiments(2000) { results.add(it) }
        h.worker.drain()
        assertEquals(listOf(true), results)
        assertEquals("b", h.client.variant("paywall_headline"))

        val slow = harness(config(exp()))
        slow.transport.respond = { req -> if (req.method == "GET") null else HttpResponse(200) }
        val slowResults = ArrayList<Boolean>()
        slow.client.loadExperiments(2000) { slowResults.add(it) }
        slow.client.loadExperiments(2000) { slowResults.add(it) }
        slow.worker.advance(1999)
        assertEquals(emptyList<Boolean>(), slowResults)
        assertEquals("one fetch for both waiters", 1, slow.transport.configFetches)
        slow.worker.advance(1)
        assertEquals(listOf(false, false), slowResults)
        // The answer arriving late still updates the config, and doesn't call anyone twice.
        val (_, complete) = slow.transport.held.single()
        complete(HttpResponse(200, config(exp())))
        slow.worker.drain()
        assertEquals(listOf(false, false), slowResults)
        assertEquals("b", slow.client.variant("paywall_headline"))
    }

    @Test
    fun foregroundRefetchesOnlyWhenOlderThanTtl() {
        val h = harness(config(exp(), ttl = 300)).launch()
        assertEquals(1, h.transport.configFetches)
        h.client.appDidEnterBackground()
        h.worker.drain()
        h.clock.now += 100_000
        h.client.appDidBecomeActive()
        h.worker.drain()
        assertEquals(1, h.transport.configFetches)
        h.client.appDidEnterBackground()
        h.worker.drain()
        h.clock.now += 200_000
        h.client.appDidBecomeActive()
        h.worker.drain()
        assertEquals(2, h.transport.configFetches)
    }

    @Test
    fun configParsingIsForgiving() {
        assertNull(ExperimentConfig.parse("[]"))
        assertNull(ExperimentConfig.parse("{\"ttl\":5}"))
        val c = ExperimentConfig.parse("""{"experiments":[{"key":"a","variants":[{"key":"x","weight":1}]},{"nokey":1}]}""")!!
        assertEquals(300L, c.ttlSeconds)
        assertEquals(listOf("a"), c.experiments.map { it.key })
        assertEquals(emptyMap<String, Any?>(), c.experiments[0].variants[0].payload)
    }
}
