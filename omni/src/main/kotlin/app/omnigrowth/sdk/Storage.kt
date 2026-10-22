package app.omnigrowth.sdk

import java.io.File
import java.io.IOException

/** Small key-value persistence (ids, flags, last seen version). Implementations must be thread-safe. */
internal interface KeyValueStore {
    fun string(key: String): String?
    fun bool(key: String): Boolean
    fun long(key: String): Long?

    /** Null removes the key. */
    fun set(key: String, value: Any?)
}

internal object StoreKey {
    const val ANON_ID = "anon_id"
    const val USER_ID = "user_id"
    const val OPTED_OUT = "opted_out"
    const val LAST_VERSION = "last_version"
    const val LAST_BUILD = "last_build"
    const val SESSION_ID = "session_id"
    const val SESSION_LAST_ACTIVITY = "session_last_activity"
    const val BUCKET_ID = "bucket_id"
    const val FIRST_LAUNCH_AT = "first_launch_at"
    const val PUSH_TOKEN = "push_token"
    const val PUSH_TOKEN_SENT_AT = "push_token_sent_at"
    const val PUSH_PERMISSION = "push_permission"
    const val ATTRIBUTION_DONE = "attribution_done"
    const val ATTRIBUTION_ATTEMPTS = "attribution_attempts"
}

/**
 * The store as one JSON file, kept in memory. Reads are instant; writes update memory at once and reach the disk on
 * [writer] (coalesced), so callers on the main thread never wait for the disk.
 *
 * Lives in `noBackupFilesDir`, not SharedPreferences: Auto Backup would restore the anon_id and bucket id onto a
 * new phone, and two devices would look like one person.
 */
internal class FileKeyValueStore(
    private val file: File,
    private val writer: (task: () -> Unit) -> Unit,
    private val log: Log = Log.off,
) : KeyValueStore {
    private val values: MutableMap<String, Any?> = LinkedHashMap()
    private var writePending = false

    init {
        val text = readText(file)
        val parsed = text?.let { Json.parseObject(it) }
        if (text != null && parsed == null) log("state file unreadable, starting fresh")
        parsed?.let { values.putAll(it) }
    }

    @Synchronized override fun string(key: String): String? = values[key] as? String
    @Synchronized override fun bool(key: String): Boolean = values[key] as? Boolean ?: false
    @Synchronized override fun long(key: String): Long? = (values[key] as? Number)?.toLong()

    override fun set(key: String, value: Any?) {
        val schedule = synchronized(this) {
            if (value == null) values.remove(key) else values[key] = value
            val first = !writePending
            writePending = true
            first
        }
        if (schedule) writer { flushToDisk() }
    }

    private fun flushToDisk() {
        val text = synchronized(this) {
            writePending = false
            Json.encode(LinkedHashMap(values))
        }
        writeText(file, text, log)
    }
}

/** Persistence for the event queue. Called only from the client's worker. */
internal interface QueueStore {
    fun load(): List<Event>
    fun save(events: List<Event>)
}

/** The queue as one JSON file (`noBackupFilesDir/omni/queue.json`), replaced atomically on every change. */
internal class FileQueueStore(private val file: File, private val log: Log = Log.off) : QueueStore {
    override fun load(): List<Event> {
        val text = readText(file) ?: return emptyList()
        val list = Json.parse(text) as? List<*>
        if (list == null) {
            log("queue file unreadable, starting empty")
            file.delete()
            return emptyList()
        }
        return list.mapNotNull { (it as? Map<*, *>)?.let(Event::fromJson) }
    }

    override fun save(events: List<Event>) {
        if (events.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        writeText(file, Json.encode(events.map { it.toJson() }), log)
    }
}

/** One text file (the experiment config copy). */
internal interface TextStore {
    fun read(): String?
    fun write(text: String)
}

internal class FileTextStore(private val file: File, private val log: Log = Log.off) : TextStore {
    override fun read(): String? = readText(file)
    override fun write(text: String) = writeText(file, text, log)
}

private fun readText(file: File): String? = try {
    if (file.exists()) file.readText(Charsets.UTF_8).takeIf { it.isNotEmpty() } else null
} catch (_: IOException) {
    null
}

/** Writes through a temporary file and a rename, so a crash mid-write never leaves half a file. */
private fun writeText(file: File, text: String, log: Log) {
    try {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("rename failed")
        }
    } catch (e: IOException) {
        log("couldn't save ${file.name}: ${e.message}")
    } catch (e: SecurityException) {
        log("couldn't save ${file.name}: ${e.message}")
    }
}
