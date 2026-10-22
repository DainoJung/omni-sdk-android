package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queueing, batching, retries and the persisted queue. */
class DeliveryTest {
    private fun quietHarness(options: Omni.Options = Omni.Options(captureLifecycle = false), queue: MemoryQueue = MemoryQueue()) =
        Harness(options = options, queue = queue, permission = null)

    @Test
    fun retryPolicyPerStatus() {
        assertEquals(RetryPolicy.Action.SUCCESS, RetryPolicy.action(200))
        assertEquals(RetryPolicy.Action.DROP, RetryPolicy.action(400))
        assertEquals(RetryPolicy.Action.DROP, RetryPolicy.action(401))
        assertEquals(RetryPolicy.Action.DROP, RetryPolicy.action(404))
        assertEquals(RetryPolicy.Action.SPLIT, RetryPolicy.action(413))
        assertEquals(RetryPolicy.Action.RETRY, RetryPolicy.action(429))
        assertEquals(RetryPolicy.Action.RETRY, RetryPolicy.action(408))
        assertEquals(RetryPolicy.Action.RETRY, RetryPolicy.action(503))
        assertEquals(RetryPolicy.Action.RETRY, RetryPolicy.action(null))
    }

    @Test
    fun backoffDoublesFromFiveSecondsToFiveMinutes() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L),
            (1..8).map(RetryPolicy::backoffMs))
        assertEquals(300_000L, RetryPolicy.backoffMs(100))
    }

    @Test
    fun sendsWhenFlushAtEventsAreQueued() {
        val h = quietHarness(Omni.Options(flushAt = 3, captureLifecycle = false))
        h.client.start()
        h.worker.drain()
        h.client.track("a", null)
        h.client.track("b", null)
        h.worker.drain()
        assertEquals(0, h.transport.batches.size)
        h.client.track("c", null)
        h.worker.drain()
        assertEquals(listOf("a", "b", "c"), h.transport.sentNames)
        assertTrue(h.queue.saved.isEmpty())
    }

    @Test
    fun sendsOnTheTimer() {
        val h = quietHarness(Omni.Options(flushIntervalSeconds = 30, captureLifecycle = false))
        h.client.start()
        h.client.track("a", null)
        h.worker.drain()
        h.worker.advance(29_000)
        assertEquals(0, h.transport.batches.size)
        h.worker.advance(1_000)
        assertEquals(listOf("a"), h.transport.sentNames)
        h.client.track("b", null)
        h.worker.advance(30_000)
        assertEquals(listOf("a", "b"), h.transport.sentNames)
    }

    @Test
    fun flushesWhenTheAppGoesToTheBackground() {
        val h = quietHarness()
        h.client.start()
        h.client.track("a", null)
        h.client.appDidEnterBackground()
        h.worker.drain()
        assertEquals(listOf("a"), h.transport.sentNames)
    }

    @Test
    fun retriesWithBackoffAndKeepsEventsOnServerErrors() {
        val h = quietHarness()
        h.transport.batchStatus = 503
        h.client.track("a", null)
        h.flush()
        assertEquals(1, h.transport.batches.size)
        assertEquals(1, h.client.retryAttempt)
        assertEquals(h.clock.now + 5_000, h.client.nextRetryAt)
        assertEquals(1, h.queue.saved.size)

        h.worker.advance(5_000)
        assertEquals(2, h.transport.batches.size)
        assertEquals(2, h.client.retryAttempt)

        h.worker.advance(9_999)
        assertEquals(2, h.transport.batches.size)
        h.transport.batchStatus = 200
        h.worker.advance(1)
        assertEquals(3, h.transport.batches.size)
        assertEquals(0, h.client.retryAttempt)
        assertNull(h.client.nextRetryAt)
        assertTrue(h.queue.saved.isEmpty())
    }

    @Test
    fun aQueuedEventDuringBackoffWaitsForTheRetry() {
        val h = quietHarness(Omni.Options(flushAt = 1, captureLifecycle = false))
        h.transport.batchStatus = null // network down
        h.client.track("a", null)
        h.worker.drain()
        assertEquals(1, h.transport.batches.size)
        h.client.track("b", null)
        h.worker.drain()
        assertEquals("flushAt doesn't override the backoff window", 1, h.transport.batches.size)
    }

    @Test
    fun dropsBatchesTheServerRejects() {
        for (status in listOf(400, 401)) {
            val h = quietHarness()
            h.transport.batchStatus = status
            h.client.track("a", null)
            h.flush()
            assertTrue(h.queue.saved.isEmpty())
            assertEquals(0, h.client.retryAttempt)
        }
    }

    @Test
    fun splitsOn413AndDropsASingleEventThatIsTooLarge() {
        val h = quietHarness()
        h.transport.respond = { req ->
            if (req.method == "GET") HttpResponse(200, h.transport.configBody) else {
                val n = (Json.parseObject(String(req.body!!))!!["batch"] as List<*>).size
                HttpResponse(if (n > 2) 413 else 200)
            }
        }
        repeat(5) { h.client.track("e$it", null) }
        h.flush()
        val sizes = h.transport.batches.map { (it["batch"] as List<*>).size }
        // 5 → too large; halves to 2 (ok), doubles to 4 for the last 3 (too large), then 1 and 2.
        assertEquals(listOf(5, 2, 3, 1, 2), sizes)
        assertEquals((0 until 5).map { "e$it" }, h.transport.sentNames.distinct().sorted())
        assertTrue(h.queue.saved.isEmpty())

        val one = quietHarness()
        one.transport.batchStatus = 413
        one.client.track("huge", null)
        one.flush()
        assertTrue(one.queue.saved.isEmpty())
    }

    @Test
    fun dropsTheOldestEventsBeyondMaxQueue() {
        val h = quietHarness(Omni.Options(maxQueue = 3, flushAt = 100, captureLifecycle = false))
        h.transport.batchStatus = 503
        (1..5).forEach { h.client.track("e$it", null) }
        h.worker.drain()
        assertEquals(listOf("e3", "e4", "e5"), h.queue.saved.map { it.event })
    }

    @Test
    fun theQueueSurvivesARelaunchAndGoesFirst() {
        val queue = MemoryQueue()
        val first = quietHarness(queue = queue)
        first.client.track("before", null)
        first.worker.drain()
        assertEquals(listOf("before"), queue.saved.map { it.event })

        val second = quietHarness(queue = queue)
        second.client.track("after", null)
        second.flush()
        assertEquals(listOf("before", "after"), second.transport.sentNames)
    }

    @Test
    fun optOutClearsTheQueueAndStopsCollection() {
        val h = quietHarness()
        h.client.track("a", null)
        h.client.optOut()
        h.client.track("b", null)
        h.flush()
        assertTrue(h.transport.batches.isEmpty())
        assertTrue(h.queue.saved.isEmpty())
        assertTrue(h.storage.bool(StoreKey.OPTED_OUT))

        h.client.optIn()
        h.client.track("c", null)
        h.flush()
        assertEquals(listOf("c"), h.transport.sentNames)
    }

    @Test
    fun anOptedOutDeviceDropsItsOldQueueOnLaunch() {
        val queue = MemoryQueue()
        val first = quietHarness(queue = queue)
        first.client.track("a", null)
        first.worker.drain()
        first.storage.set(StoreKey.OPTED_OUT, true)
        val second = Harness(options = Omni.Options(captureLifecycle = false), storage = first.storage, queue = queue, permission = null)
        second.flush()
        assertTrue(queue.saved.isEmpty())
        assertTrue(second.transport.batches.isEmpty())
    }

    @Test
    fun ignoresBadNamesAndOmniOwnNames() {
        val h = quietHarness()
        h.client.track("", null)
        h.client.track("   ", null)
        h.client.track("x".repeat(201), null)
        h.client.track("\$screen", null)
        h.client.track("  ok  ", null)
        assertEquals(listOf("ok"), h.names())
    }

    @Test
    fun optionsArePulledIntoRange() {
        val o = Omni.Options(flushAt = 5000, maxQueue = 0, flushIntervalSeconds = -3).normalized()
        assertEquals(1, o.maxQueue)
        assertEquals(1, o.flushAt)
        assertEquals(1, o.flushIntervalSeconds)
        assertEquals("https://api.omnigrowth.app", Omni.Options().baseUrl())
        assertEquals("http://localhost:4545", Omni.Options(host = " http://localhost:4545// ").baseUrl())
        assertNull(Omni.Options(host = "api.omnigrowth.app").baseUrl())
        assertNull(Omni.Options(host = "ftp://x.y").baseUrl())
    }
}
