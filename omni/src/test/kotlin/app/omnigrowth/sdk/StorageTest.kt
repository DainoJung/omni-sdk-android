package app.omnigrowth.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun keyValueStoreWritesThroughAndReadsBack() {
        val file = File(tmp.root, "omni/state.json")
        val pending = ArrayList<() -> Unit>()
        val store = FileKeyValueStore(file, writer = { pending.add(it) })
        store.set("anon_id", "a1")
        store.set("opted_out", true)
        store.set("first_launch_at", 1_792_573_198_000L)
        assertEquals("reads come from memory before the disk write", "a1", store.string("anon_id"))
        assertEquals("writes are coalesced", 1, pending.size)
        pending.forEach { it() }

        val again = FileKeyValueStore(file, { it() })
        assertEquals("a1", again.string("anon_id"))
        assertTrue(again.bool("opted_out"))
        assertEquals(1_792_573_198_000L, again.long("first_launch_at"))
        again.set("anon_id", null)
        assertNull(FileKeyValueStore(file, { it() }).string("anon_id"))
    }

    @Test
    fun aCorruptStateFileStartsFresh() {
        val file = File(tmp.root, "state.json").apply { writeText("{nope") }
        assertNull(FileKeyValueStore(file, { it() }).string("anon_id"))
    }

    @Test
    fun queueFileRoundTripsAndEmptiesAway() {
        val file = File(tmp.root, "omni/queue.json")
        val store = FileQueueStore(file)
        val e = Event("u1", "a", "2026-10-21T08:59:58.120Z", "anon", null, mapOf("k" to "v"), EventContext(platform = "android"), null)
        store.save(listOf(e, e.copy(uuid = "u2")))
        assertEquals(listOf("u1", "u2"), FileQueueStore(file).load().map { it.uuid })
        store.save(emptyList())
        assertFalse(file.exists())
        file.writeText("garbage")
        assertEquals(emptyList<Event>(), FileQueueStore(file).load())
    }
}
