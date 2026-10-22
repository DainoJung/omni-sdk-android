package app.omnigrowth.sdk

import java.util.UUID

/**
 * The engine behind the [Omni] facade. Identity (anon_id, user_id, opt-out), screens and experiments are guarded by
 * a lock so they can be read from any thread and snapshotted at call time; everything else (queue, session, sending)
 * is confined to the [worker] thread.
 */
internal class OmniClient(
    val key: String,
    val options: Omni.Options,
    private val batchUrl: String,
    private val configUrl: String,
    private val storage: KeyValueStore,
    private val queueStore: QueueStore,
    private val configStore: TextStore,
    private val transport: Transport,
    private val device: DeviceInfo,
    private val worker: Worker,
    val log: Log,
    /** `authorized` / `denied`, or null when it can't be read. Called on the worker. */
    private val pushPermission: () -> String? = { null },
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val SESSION_TIMEOUT_MS = 30L * 60 * 1000
        const val MAX_BATCH_SIZE = 100
        const val MAX_BODY_BYTES = 500_000
        const val MAX_EVENT_NAME_LENGTH = 200
        const val SDK = "omni-android/${Omni.sdkVersion}"
        private const val RECENT_PUSH_OPENS = 50

        fun newId(): String = UUID.randomUUID().toString()

        /** A persisted id, created (and persisted) on first use. */
        fun storedId(storage: KeyValueStore, key: String): String {
            storage.string(key)?.takeIf { it.isNotEmpty() }?.let { return it }
            return newId().also { storage.set(key, it) }
        }
    }

    // Guarded by `lock`.
    private val lock = Any()
    private var anonId: String = storedId(storage, StoreKey.ANON_ID)
    private var userId: String? = storage.string(StoreKey.USER_ID)
    private var optedOut: Boolean = storage.bool(StoreKey.OPTED_OUT)
    private val screens = ScreenDeduper()
    private var config: ExperimentConfig? = null
    private var configFetchedAt: Long? = null
    private var configLoaded = false
    private val exposed = HashSet<String>()
    /** When the last event was queued (mirrors the worker's session clock), to see a session has run out. */
    private var lastEventAt: Long? = storage.long(StoreKey.SESSION_LAST_ACTIVITY)
    private val recentPushOpens = ArrayDeque<String>()

    /** Never sent anywhere, and survives `reset()`: logging out doesn't move a person to another variant. */
    private val bucketId: String = storedId(storage, StoreKey.BUCKET_ID)

    // Worker-confined.
    private val events = ArrayList<Event>()
    private var sessionId: String? = storage.string(StoreKey.SESSION_ID)
    private var lastActivity: Long? = storage.long(StoreKey.SESSION_LAST_ACTIVITY)
    private var backgroundedAt: Long? = null
    private var wasBackgrounded = false
    private var coldOpenPending = false
    private var isSending = false
    private var batchLimit = MAX_BATCH_SIZE
    internal var retryAttempt = 0
        private set
    internal var nextRetryAt: Long? = null
        private set
    private var retryTask: Cancellable? = null
    private var timerTask: Cancellable? = null
    private val flushCompletions = ArrayList<() -> Unit>()
    private var fetchingConfig = false
    private val configWaiters = ArrayList<(Boolean) -> Unit>()

    init {
        // A real first launch: remember when, for experiments limited to new users. Done here, not on the worker,
        // so `variant()` right after `configure` already knows. Updates from versions without Omni count as old.
        if (storage.string(StoreKey.LAST_VERSION) == null && storage.string(StoreKey.LAST_BUILD) == null &&
            storage.long(StoreKey.FIRST_LAUNCH_AT) == null && device.isFreshInstall != false
        ) {
            storage.set(StoreKey.FIRST_LAUNCH_AT, now())
        }

        // The persisted queue loads first on the worker, so anything tracked meanwhile lands after it.
        val wasOptedOut = optedOut
        worker.post {
            val loaded = queueStore.load()
            if (wasOptedOut) {
                if (loaded.isNotEmpty()) queueStore.save(emptyList())
                return@post
            }
            events.addAll(0, loaded)
            if (events.size > options.maxQueue) {
                events.subList(0, events.size - options.maxQueue).clear()
                queueStore.save(events)
            }
            if (loaded.isNotEmpty()) log("restored ${loaded.size} queued events")
        }
    }

    val anonymousId: String get() = synchronized(lock) { anonId }
    val currentUserId: String? get() = synchronized(lock) { userId }
    val isOptedOut: Boolean get() = synchronized(lock) { optedOut }

    // Public operations (any thread)

    fun track(name: String, properties: Map<String, Any?>?) {
        val valid = validName(name) ?: return
        if (valid.startsWith("$")) {
            log("ignored track(\"$valid\"): names starting with $ are Omni's own")
            return
        }
        capture(valid, Sanitizer.properties(properties, log))
    }

    fun screen(name: String, properties: Map<String, Any?>?) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            log("ignored screen with an empty name")
            return
        }
        val props = LinkedHashMap(Sanitizer.properties(properties, log))
        props["name"] = trimmed
        synchronized(lock) { screens.record(trimmed) }
        capture("\$screen", props)
    }

    /** `$screen` for a screen the SDK saw appear. Skipped when it's the same screen as just before. */
    fun observedScreen(name: String, auto: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || (auto && !options.captureScreens)) return
        if (!synchronized(lock) { screens.shouldSend(trimmed) }) return
        capture("\$screen", linkedMapOf("name" to trimmed, "auto" to auto))
    }

    fun identify(userId: String, traits: Map<String, Any?>?) {
        val id = userId.trim()
        if (id.isEmpty()) {
            log("ignored identify: empty user id")
            return
        }
        val clean = Sanitizer.properties(traits, log)
        synchronized(lock) { this.userId = id }
        storage.set(StoreKey.USER_ID, id)
        capture("\$identify", identity = if (clean.isEmpty()) null else Identity(traits = clean))
    }

    fun setTraits(traits: Map<String, Any?>?) {
        val clean = Sanitizer.properties(traits, log)
        if (clean.isEmpty()) {
            log("ignored setTraits: no encodable traits")
            return
        }
        capture("\$set", identity = Identity(traits = clean))
    }

    fun setEmail(email: String, consent: Boolean) {
        setTraits(linkedMapOf("email" to email, "email_consent" to consent))
    }

    fun setBillingId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) {
            log("ignored setBillingId: empty id")
            return
        }
        capture("\$set", identity = Identity(billingId = trimmed))
    }

    fun reset() {
        val anon = newId()
        synchronized(lock) {
            anonId = anon
            userId = null
            screens.clear()
            exposed.clear()
        }
        storage.set(StoreKey.ANON_ID, anon)
        storage.set(StoreKey.USER_ID, null)
        worker.post {
            sessionId = null // the next event starts a new session
            log("reset: new anon_id $anon")
            // The device's push token now belongs to the new anonymous person.
            storage.string(StoreKey.PUSH_TOKEN)?.let { sendPushToken(it, force = true) }
        }
    }

    /** `$delete_user` carries the ids as they are now, so Omni can find everything about this person; then a fresh start. */
    fun deleteUser() {
        capture("\$delete_user")
        reset()
        flush()
    }

    fun optOut() {
        synchronized(lock) { optedOut = true }
        storage.set(StoreKey.OPTED_OUT, true)
        worker.post {
            events.clear()
            queueStore.save(emptyList())
            retryTask?.cancel()
            retryTask = null
            nextRetryAt = null
            retryAttempt = 0
            log("opted out: queue cleared, collection stopped")
        }
    }

    fun optIn() {
        synchronized(lock) { optedOut = false }
        storage.set(StoreKey.OPTED_OUT, false)
        log("opted in")
    }

    /** Sends everything queued now (ignores backoff). [completion] runs on the worker when the queue is empty or a send failed. */
    fun flush(completion: (() -> Unit)? = null) {
        worker.post { flushOnWorker(force = true, completion = completion) }
    }

    // Experiments

    fun variant(key: String): String? = resolve(key)?.variant

    fun payload(key: String): Map<String, Any?>? = resolve(key)?.payload

    private fun resolve(key: String): Choice? {
        val experiment = key.trim()
        var expose = false
        val choice = synchronized(lock) {
            loadConfigCopy()
            // 30 minutes without events: the next event starts a new session, so exposures count afresh.
            val t = now()
            if (lastEventAt?.let { t - it >= SESSION_TIMEOUT_MS } == true) {
                exposed.clear()
                lastEventAt = t
            }
            val exp = config?.find(experiment) ?: return null
            val c = ExperimentConfig.choose(exp, device.platform, storage.long(StoreKey.FIRST_LAUNCH_AT), bucketId)
                ?: return null
            expose = !c.decided && !optedOut && exposed.add(experiment)
            c
        }
        if (expose) capture("\$exposure", linkedMapOf("experiment" to experiment, "variant" to choice.variant))
        return choice
    }

    /** The device's copy of the config, read once (on first use or at start). Caller holds `lock`. */
    private fun loadConfigCopy() {
        if (configLoaded) return
        configLoaded = true
        val text = configStore.read() ?: return
        val stored = Json.parseObject(text) ?: return
        val body = stored["config"] as? String ?: return
        config = ExperimentConfig.parse(body)
        configFetchedAt = (stored["fetched_at"] as? Number)?.toLong()
    }

    /**
     * Fetches the config now and calls [completion] (on the worker) with true once it's in, or false after
     * [timeoutMs] or when the fetch failed. The last copy stays in use either way.
     */
    fun loadExperiments(timeoutMs: Long, completion: (Boolean) -> Unit) {
        worker.post {
            var done = false
            var timeout: Cancellable? = null
            val finish: (Boolean) -> Unit = { ok ->
                if (!done) {
                    done = true
                    timeout?.cancel()
                    completion(ok)
                }
            }
            timeout = worker.postDelayed(timeoutMs.coerceAtLeast(0)) { finish(false) }
            configWaiters.add(finish)
            fetchConfig()
        }
    }

    private fun fetchConfig() {
        if (fetchingConfig) return
        fetchingConfig = true
        transport.send(HttpRequest("GET", configUrl, headers = mapOf("User-Agent" to SDK))) { response ->
            worker.post { handleConfig(response) }
        }
    }

    private fun handleConfig(response: HttpResponse) {
        fetchingConfig = false
        val body = response.body
        val parsed = if (response.status in 200..299 && body != null) ExperimentConfig.parse(body) else null
        if (parsed != null) {
            val at = now()
            synchronized(lock) {
                configLoaded = true
                config = parsed
                configFetchedAt = at
            }
            configStore.write(Json.encode(linkedMapOf("fetched_at" to at, "config" to body)))
            log("experiments: ${parsed.experiments.size} listed")
        } else {
            log("experiments: fetch failed (${response.status ?: response.error?.message}); keeping the last copy")
        }
        val waiters = ArrayList(configWaiters)
        configWaiters.clear()
        waiters.forEach { it(parsed != null) }
    }

    private fun refreshConfigIfStale() {
        val (fetchedAt, ttl) = synchronized(lock) {
            loadConfigCopy()
            configFetchedAt to (config?.ttlSeconds ?: ExperimentConfig.DEFAULT_TTL_SECONDS)
        }
        val t = now()
        if (fetchedAt == null || t - fetchedAt >= ttl * 1000 || t < fetchedAt) fetchConfig()
    }

    // Push

    fun setPushToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            log("ignored setPushToken: empty token")
            return
        }
        worker.post { sendPushToken(trimmed, force = false) }
    }

    private fun sendPushToken(token: String, force: Boolean) {
        if (isOptedOut) return
        val t = now()
        if (!force && !Push.shouldSendToken(token, storage.string(StoreKey.PUSH_TOKEN), storage.long(StoreKey.PUSH_TOKEN_SENT_AT), t)) {
            return
        }
        captureOnWorker("\$push_token", Push.tokenProperties(token))
        storage.set(StoreKey.PUSH_TOKEN, token)
        storage.set(StoreKey.PUSH_TOKEN_SENT_AT, t)
    }

    private fun checkPushPermission() {
        if (isOptedOut) return
        val status = try {
            pushPermission()
        } catch (_: Exception) {
            null
        } ?: return
        if (status == storage.string(StoreKey.PUSH_PERMISSION)) return
        captureOnWorker("\$push_permission", linkedMapOf("status" to status))
        storage.set(StoreKey.PUSH_PERMISSION, status)
    }

    /** `$push_opened` once per message (an app that also calls `trackPushOpened` for the same intent isn't counted twice). */
    fun pushOpened(messageId: String) {
        val first = synchronized(lock) {
            if (messageId in recentPushOpens) {
                false
            } else {
                recentPushOpens.addLast(messageId)
                if (recentPushOpens.size > RECENT_PUSH_OPENS) recentPushOpens.removeFirst()
                true
            }
        }
        if (first) capture("\$push_opened", linkedMapOf("message_id" to messageId))
    }

    // Attribution

    val needsAttribution: Boolean get() = !storage.bool(StoreKey.ATTRIBUTION_DONE) && !isOptedOut

    fun recordAttribution(result: Attribution.Result) {
        worker.post {
            when (result) {
                is Attribution.Result.Found -> {
                    captureOnWorker("\$attribution", Attribution.properties(result))
                    storage.set(StoreKey.ATTRIBUTION_DONE, true)
                }
                Attribution.Result.NotAvailable -> {
                    log("attribution: no Play Store install referrer on this device")
                    storage.set(StoreKey.ATTRIBUTION_DONE, true)
                }
                Attribution.Result.TryLater -> {
                    val attempts = (storage.long(StoreKey.ATTRIBUTION_ATTEMPTS) ?: 0) + 1
                    storage.set(StoreKey.ATTRIBUTION_ATTEMPTS, attempts)
                    if (attempts >= Attribution.MAX_ATTEMPTS) storage.set(StoreKey.ATTRIBUTION_DONE, true)
                    log("attribution: the Play Store didn't answer (attempt $attempts)")
                }
            }
        }
    }

    // Lifecycle

    /**
     * Cold start: install/update detection, the flush timer, the experiment config and push permission. On Android the
     * process often starts with nothing on screen (a push, a job), so the first `$app_opened` waits for the app to
     * come to the foreground unless [appInForeground].
     */
    fun start(appInForeground: Boolean = false) {
        worker.post {
            detectInstallOrUpdate()
            if (options.captureLifecycle) {
                if (appInForeground) {
                    captureOnWorker("\$app_opened", linkedMapOf("from_background" to false))
                } else {
                    coldOpenPending = true
                }
            }
            startTimer()
            fetchConfig()
            checkPushPermission()
            storage.string(StoreKey.PUSH_TOKEN)?.let { sendPushToken(it, force = false) }
        }
    }

    fun appDidBecomeActive() {
        worker.post {
            val since = backgroundedAt
            if (since != null && now() - since >= SESSION_TIMEOUT_MS) sessionId = null
            backgroundedAt = null
            val coldOpen = coldOpenPending
            val fromBackground = wasBackgrounded
            coldOpenPending = false
            wasBackgrounded = false
            if (options.captureLifecycle) {
                if (coldOpen) {
                    captureOnWorker("\$app_opened", linkedMapOf("from_background" to false))
                } else if (fromBackground) {
                    captureOnWorker("\$app_opened", linkedMapOf("from_background" to true))
                }
            }
            if (coldOpen || fromBackground) {
                refreshConfigIfStale()
                checkPushPermission()
                storage.string(StoreKey.PUSH_TOKEN)?.let { sendPushToken(it, force = false) }
            }
        }
    }

    fun appDidEnterBackground(completion: (() -> Unit)? = null) {
        worker.post {
            backgroundedAt = now()
            wasBackgrounded = true
            flushOnWorker(force = true, completion = completion)
        }
    }

    private fun detectInstallOrUpdate() {
        val version = device.appVersion.orEmpty()
        val build = device.build.orEmpty()
        val previousVersion = storage.string(StoreKey.LAST_VERSION)
        val previousBuild = storage.string(StoreKey.LAST_BUILD)
        if (options.captureLifecycle) {
            val neverSeen = previousVersion == null && previousBuild == null
            if (neverSeen && device.isFreshInstall != false) {
                captureOnWorker("\$app_installed", linkedMapOf("version" to version, "build" to build))
            } else if (neverSeen || previousVersion != version || previousBuild != build) {
                // `neverSeen` here: the app was updated to the first version with Omni, so it isn't a new install.
                captureOnWorker(
                    "\$app_updated",
                    linkedMapOf(
                        "version" to version,
                        "build" to build,
                        "previous_version" to previousVersion.orEmpty(),
                        "previous_build" to previousBuild.orEmpty(),
                    ),
                )
            }
        }
        storage.set(StoreKey.LAST_VERSION, version)
        storage.set(StoreKey.LAST_BUILD, build)
    }

    // Capture

    private fun validName(name: String): String? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            log("ignored event with an empty name")
            return null
        }
        if (trimmed.length > MAX_EVENT_NAME_LENGTH) {
            log("ignored event \"${trimmed.take(40)}…\": names are at most $MAX_EVENT_NAME_LENGTH characters")
            return null
        }
        return trimmed
    }

    /** Snapshots time and identity now, then queues the event in order. */
    fun capture(name: String, properties: Map<String, Any?> = emptyMap(), identity: Identity? = null) {
        val ts = now()
        val (anon, user, out) = synchronized(lock) { Triple(anonId, userId, optedOut) }
        if (out) {
            log("opted out: dropped $name")
            return
        }
        worker.post { enqueue(name, properties, identity, ts, anon, user) }
    }

    /** [capture] for code already on the worker (lifecycle, push), so those events keep their place. */
    private fun captureOnWorker(name: String, properties: Map<String, Any?>) {
        val (anon, user, out) = synchronized(lock) { Triple(anonId, userId, optedOut) }
        if (out) return
        enqueue(name, properties, null, now(), anon, user)
    }

    private fun enqueue(name: String, properties: Map<String, Any?>, identity: Identity?, ts: Long, anonId: String, userId: String?) {
        val context = context(ts)
        if (name == "\$exposure") {
            // A new session may have just started (and cleared exposures); this one counts in it,
            // unless `reset()` came in between (the new person hasn't been exposed yet).
            (properties["experiment"] as? String)?.let { synchronized(lock) { if (anonId == this.anonId) exposed.add(it) } }
        }
        val event = Event(
            uuid = newId(),
            event = name,
            ts = Timestamp.format(ts),
            anonId = anonId,
            userId = userId,
            properties = properties,
            context = context,
            identity = identity,
        )
        events.add(event)
        if (events.size > options.maxQueue) {
            val overflow = events.size - options.maxQueue
            events.subList(0, overflow).clear()
            log("queue full (${options.maxQueue}): dropped $overflow oldest event(s)")
        }
        queueStore.save(events)
        log("queued $name (${events.size} waiting)")
        if (events.size >= options.flushAt) flushOnWorker(force = false)
    }

    private fun context(ts: Long): EventContext {
        val last = lastActivity
        val newSession = sessionId == null || last == null || ts - last >= SESSION_TIMEOUT_MS
        if (newSession) {
            sessionId = newId()
            storage.set(StoreKey.SESSION_ID, sessionId)
        }
        synchronized(lock) {
            // `$exposure` is once per experiment per session.
            if (newSession) exposed.clear()
            lastEventAt = ts
        }
        lastActivity = ts
        storage.set(StoreKey.SESSION_LAST_ACTIVITY, ts)
        return EventContext(
            appVersion = device.appVersion,
            build = device.build,
            platform = device.platform,
            deviceType = device.deviceType,
            locale = device.locale,
            osVersion = device.osVersion,
            deviceModel = device.deviceModel,
            sessionId = sessionId,
            sdk = SDK,
        )
    }

    // Sending (worker only)

    private fun startTimer() {
        timerTask?.cancel()
        val interval = options.flushIntervalSeconds * 1000L
        fun schedule() {
            timerTask = worker.postDelayed(interval) {
                if (events.isNotEmpty()) flushOnWorker(force = false)
                schedule()
            }
        }
        schedule()
    }

    /** [force] ignores the backoff window (explicit flush, going to background, the retry itself). */
    private fun flushOnWorker(force: Boolean, completion: (() -> Unit)? = null) {
        completion?.let { flushCompletions.add(it) }
        if (isSending) return // completions run when the current send cycle ends
        val next = nextRetryAt
        if (!force && next != null && now() < next) {
            finishFlush()
            return
        }
        sendNextBatch()
    }

    private fun sendNextBatch() {
        if (isOptedOut || events.isEmpty()) {
            finishFlush()
            return
        }
        var count = minOf(batchLimit, events.size, MAX_BATCH_SIZE)
        var body: ByteArray
        while (true) {
            body = BatchBody.encode(key, Timestamp.format(now()), events.subList(0, count)).toByteArray(Charsets.UTF_8)
            if (body.size > MAX_BODY_BYTES && count > 1) {
                count = maxOf(1, count / 2)
                continue
            }
            break
        }
        val ids = events.subList(0, count).map { it.uuid }.toHashSet()
        val request = HttpRequest(
            method = "POST",
            url = batchUrl,
            body = body,
            headers = mapOf("Content-Type" to "application/json", "User-Agent" to SDK),
        )
        isSending = true
        log("sending $count event(s)")
        transport.send(request) { response ->
            worker.post { handleResponse(response, ids, count) }
        }
    }

    private fun handleResponse(response: HttpResponse, ids: Set<String>, count: Int) {
        isSending = false
        val status = response.status
        when (RetryPolicy.action(status)) {
            RetryPolicy.Action.SUCCESS -> {
                remove(ids)
                retryAttempt = 0
                nextRetryAt = null
                retryTask?.cancel()
                retryTask = null
                batchLimit = minOf(MAX_BATCH_SIZE, batchLimit * 2)
                log("sent $count event(s)")
                sendNextBatch()
            }
            RetryPolicy.Action.DROP -> {
                remove(ids)
                if (status == 401) {
                    Log.warn("HTTP 401: the key was rejected; dropped $count event(s). Check the key passed to Omni.configure.")
                    finishFlush()
                } else {
                    log("HTTP $status: the server rejected the batch; dropped $count event(s)")
                    sendNextBatch()
                }
            }
            RetryPolicy.Action.SPLIT -> {
                if (count <= 1) {
                    remove(ids)
                    log("HTTP 413: a single event is too large; dropped it")
                } else {
                    batchLimit = maxOf(1, count / 2)
                    log("HTTP 413: retrying in batches of $batchLimit")
                }
                sendNextBatch()
            }
            RetryPolicy.Action.RETRY -> {
                retryAttempt += 1
                val delay = RetryPolicy.backoffMs(retryAttempt)
                nextRetryAt = now() + delay
                scheduleRetry(delay)
                val why = status?.let { "HTTP $it" } ?: "network error (${response.error?.message ?: "unknown"})"
                log("$why: keeping ${events.size} event(s), retrying in ${delay / 1000}s")
                finishFlush()
            }
        }
    }

    private fun scheduleRetry(delayMs: Long) {
        retryTask?.cancel()
        retryTask = worker.postDelayed(delayMs) {
            retryTask = null
            flushOnWorker(force = true)
        }
    }

    private fun remove(ids: Set<String>) {
        events.removeAll { it.uuid in ids }
        queueStore.save(events)
    }

    private fun finishFlush() {
        if (flushCompletions.isEmpty()) return
        val completions = ArrayList(flushCompletions)
        flushCompletions.clear()
        completions.forEach { it() }
    }

    /** Queued events, for tests. Worker only. */
    internal fun queuedForTest(): List<Event> = ArrayList(events)
}
