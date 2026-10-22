package app.omnigrowth.sdk

/** A clock the tests move by hand. */
class FakeClock(var now: Long = Timestamp.parse("2026-10-21T09:00:00.000Z")!!)

/** A [Worker] that runs nothing until the test says so: [drain] runs posted tasks, [advance] moves time. */
internal class FakeWorker(private val clock: FakeClock) : Worker {
    private class Delayed(val at: Long, val seq: Long, val task: () -> Unit) {
        var cancelled = false
    }

    private val tasks = ArrayDeque<() -> Unit>()
    private val delayed = ArrayList<Delayed>()
    private var seq = 0L

    override fun post(task: () -> Unit) {
        tasks.addLast(task)
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val d = Delayed(clock.now + delayMs, seq++, task)
        delayed.add(d)
        return Cancellable { d.cancelled = true }
    }

    fun drain() {
        while (tasks.isNotEmpty()) tasks.removeFirst()()
    }

    fun advance(ms: Long) {
        val target = clock.now + ms
        while (true) {
            drain()
            val next = delayed.filter { !it.cancelled && it.at <= target }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            delayed.remove(next)
            clock.now = next.at
            next.task()
        }
        clock.now = target
        drain()
    }
}

/** Records requests; answers with [respond] at once, or holds them when it returns null. */
internal class FakeTransport : Transport {
    val requests = ArrayList<HttpRequest>()
    val held = ArrayList<Pair<HttpRequest, (HttpResponse) -> Unit>>()
    var configBody = """{"experiments":[],"ttl":300}"""
    var batchStatus: Int? = 200
    var respond: (HttpRequest) -> HttpResponse? = { req ->
        if (req.method == "GET") HttpResponse(200, configBody) else HttpResponse(batchStatus, "{\"ok\":true}")
    }

    override fun send(request: HttpRequest, completion: (HttpResponse) -> Unit) {
        requests.add(request)
        val r = respond(request)
        if (r != null) completion(r) else held.add(request to completion)
    }

    val batches: List<Map<String, Any?>>
        get() = requests.filter { it.method == "POST" }.map { Json.parseObject(String(it.body!!, Charsets.UTF_8))!! }

    @Suppress("UNCHECKED_CAST")
    val sentEvents: List<Map<String, Any?>>
        get() = batches.flatMap { it["batch"] as List<Map<String, Any?>> }

    val sentNames: List<String> get() = sentEvents.map { it["event"] as String }

    val configFetches: Int get() = requests.count { it.method == "GET" }
}

internal class MemoryStore : KeyValueStore {
    val values = HashMap<String, Any?>()
    @Synchronized override fun string(key: String): String? = values[key] as? String
    @Synchronized override fun bool(key: String): Boolean = values[key] as? Boolean ?: false
    @Synchronized override fun long(key: String): Long? = (values[key] as? Number)?.toLong()
    @Synchronized override fun set(key: String, value: Any?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

internal class MemoryQueue : QueueStore {
    var saved: List<Event> = emptyList()
    override fun load(): List<Event> = saved
    override fun save(events: List<Event>) {
        saved = ArrayList(events)
    }
}

internal class MemoryText : TextStore {
    var text: String? = null
    override fun read(): String? = text
    override fun write(text: String) {
        this.text = text
    }
}

internal val testDevice = DeviceInfo(
    appVersion = "1.4.0",
    build = "52",
    platform = "android",
    deviceType = "phone",
    locale = "ko-KR",
    osVersion = "16",
    deviceModel = "Pixel 9",
    isFreshInstall = true,
)

/** One client with fakes all around. Share [storage], [queue] and [configStore] to simulate a relaunch. */
internal class Harness(
    val options: Omni.Options = Omni.Options(flushAt = 20),
    val device: DeviceInfo = testDevice,
    val storage: MemoryStore = MemoryStore(),
    val queue: MemoryQueue = MemoryQueue(),
    val configStore: MemoryText = MemoryText(),
    val clock: FakeClock = FakeClock(),
    var permission: String? = "authorized",
) {
    val worker = FakeWorker(clock)
    val transport = FakeTransport()
    val client = OmniClient(
        key = "opk_test",
        options = options.normalized(),
        batchUrl = "https://api.omnigrowth.app/v1/batch",
        configUrl = "https://api.omnigrowth.app/v1/config?key=opk_test",
        storage = storage,
        queueStore = queue,
        configStore = configStore,
        transport = transport,
        device = device,
        worker = worker,
        log = Log.off,
        pushPermission = { permission },
        now = { clock.now },
    )

    /** Starts the client and comes to the foreground, as a normal cold launch does. */
    fun launch(): Harness {
        client.start()
        client.appDidBecomeActive()
        worker.drain()
        return this
    }

    fun flush() {
        client.flush()
        worker.drain()
    }

    /** Names of what's queued or already sent, in order. */
    fun names(): List<String> {
        worker.drain()
        return transport.sentNames + client.queuedForTest().map { it.event }
    }

    fun events(): List<Map<String, Any?>> {
        flush()
        return transport.sentEvents
    }
}
