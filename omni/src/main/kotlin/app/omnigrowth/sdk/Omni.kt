package app.omnigrowth.sdk

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Omni: send product events from your app to Omni Cloud.
 *
 * ```kotlin
 * Omni.configure(this, "opk_…")             // in Application.onCreate
 * Omni.track("lesson_completed", mapOf("lesson" to 3))
 * ```
 *
 * Every call is thread-safe, returns immediately and never throws. Work happens on a background thread.
 */
public object Omni {
    public const val sdkVersion: String = "0.2.0"

    /** Called when [loadExperiments] is done: [fetched] is true when a fresh config arrived before the timeout. */
    public fun interface ExperimentsCallback {
        public fun onLoaded(fetched: Boolean)
    }

    public class Options @JvmOverloads constructor(
        /** Omni Cloud's base URL. Events go to `<host>/v1/batch`. */
        public val host: String = "https://api.omnigrowth.app",
        /** Send once this many events are queued. */
        public val flushAt: Int = 20,
        /** Send queued events at least this often, in seconds. */
        public val flushIntervalSeconds: Int = 30,
        /** Most events kept on the device while offline; the oldest are dropped first. */
        public val maxQueue: Int = 1000,
        /** Send `$screen` automatically each time an activity resumes, with `auto: true`. Compose screens: [screen]. */
        public val captureScreens: Boolean = true,
        /** Log what the SDK does to Logcat (tag `Omni`). */
        public val debug: Boolean = false,
        /**
         * Send `$app_installed`, `$app_updated` and `$app_opened` automatically.
         * (Flushing when the app goes to the background happens either way.)
         */
        public val captureLifecycle: Boolean = true,
        /**
         * The app shows Omni's pushes itself by calling [showPush] from its `FirebaseMessagingService`, so Omni may
         * send them with buttons (they then come as data-only messages). Without it, pushes come without buttons.
         */
        public val showsPushes: Boolean = false,
    ) {
        /** The same options with out-of-range values pulled into range. */
        internal fun normalized(): Options {
            val queue = maxOf(1, maxQueue)
            return Options(
                host = host,
                flushAt = flushAt.coerceIn(1, queue),
                flushIntervalSeconds = maxOf(1, flushIntervalSeconds),
                maxQueue = queue,
                captureScreens = captureScreens,
                debug = debug,
                captureLifecycle = captureLifecycle,
                showsPushes = showsPushes,
            )
        }

        /** `host` without trailing slashes, or null when it isn't an http(s) URL. */
        internal fun baseUrl(): String? {
            val base = host.trim().trimEnd('/')
            return try {
                val uri = URI(base)
                val scheme = uri.scheme?.lowercase()
                if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrEmpty()) base else null
            } catch (_: Exception) {
                null
            }
        }
    }

    // Setup

    /**
     * Starts Omni. Call once, in your `Application.onCreate`. Later calls are ignored.
     *
     * @param key your app's public write key (`opk_…`), shown in Omni.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, key: String, options: Options = Options()) {
        val opts = options.normalized()
        val writeKey = key.trim()
        if (writeKey.isEmpty()) {
            Log.warn("configure: the key is empty, so Omni stays off.")
            return
        }
        val base = opts.baseUrl()
        if (base == null) {
            Log.warn("configure: \"${opts.host}\" isn't a valid host, so Omni stays off.")
            return
        }
        if (opts.debug && !writeKey.startsWith("opk_")) Log.warn("configure: keys normally start with opk_. Is this the right key?")

        synchronized(this) {
            sharedClient?.let {
                it.log("configure called again; ignored")
                return
            }
            val app = context.applicationContext ?: context
            appContext = app
            val log = Log(opts.debug)
            val worker = ExecutorWorker("omni")
            val dir = File(app.noBackupFilesDir, "omni")
            val device = DeviceInfo.collect(app)
            val client = OmniClient(
                key = writeKey,
                options = opts,
                batchUrl = "$base/v1/batch",
                configUrl = "$base/v1/config?key=" + URLEncoder.encode(writeKey, "UTF-8"),
                storage = FileKeyValueStore(File(dir, "state.json"), worker::post, log),
                queueStore = FileQueueStore(File(dir, "queue.json"), log),
                configStore = FileTextStore(File(dir, "config.json"), log),
                transport = HttpUrlConnectionTransport(),
                device = device,
                worker = worker,
                log = log,
                pushPermission = {
                    if (NotificationManagerCompat.from(app).areNotificationsEnabled()) "authorized" else "denied"
                },
            )
            when (pendingOptOut) {
                true -> client.optOut()
                false -> client.optIn()
                null -> {}
            }
            pendingOptOut = null
            sharedClient = client

            client.start()
            (app as? Application)?.let { AndroidHooks(client).install(it) }
                ?: Log.warn("configure: no Application context, so screens and foreground/background aren't tracked.")
            if (client.needsAttribution) {
                Attribution.read(app, background = { task -> worker.post(task) }, done = client::recordAttribution)
            }
            log("configured (${device.platform}, ${device.deviceModel ?: "unknown model"}, anon_id ${client.anonymousId})")
        }
    }

    // Events

    /**
     * Records an event. Names starting with `$` are Omni's own and are ignored here.
     * Property values may be strings, numbers, booleans, dates, lists and maps of these; anything else is dropped.
     */
    @JvmStatic
    @JvmOverloads
    public fun track(event: String, properties: Map<String, Any?>? = null) {
        client("track")?.track(event, properties)
    }

    /**
     * Records a screen view (`$screen`). Activities are recorded automatically (see [Options.captureScreens]);
     * call this for Compose destinations and fragments.
     */
    @JvmStatic
    @JvmOverloads
    public fun screen(name: String, properties: Map<String, Any?>? = null) {
        client("screen")?.screen(name, properties)
    }

    // Identity

    /** Links this device to your own user id (`$identify`). Call after sign-in. Traits are person properties. */
    @JvmStatic
    @JvmOverloads
    public fun identify(userId: String, traits: Map<String, Any?>? = null) {
        client("identify")?.identify(userId, traits)
    }

    /** Updates person properties (`$set`). */
    @JvmStatic
    public fun setTraits(traits: Map<String, Any?>) {
        client("setTraits")?.setTraits(traits)
    }

    /** Sets the person's email. Pass `consent = true` only after they agreed to marketing email. */
    @JvmStatic
    public fun setEmail(email: String, consent: Boolean) {
        client("setEmail")?.setEmail(email, consent)
    }

    /**
     * Tells Omni who this person is on the billing side, so revenue joins product events.
     * Pass RevenueCat's `Purchases.sharedInstance.appUserID` or the Stripe customer id (`cus_…`).
     */
    @JvmStatic
    public fun setBillingId(id: String) {
        client("setBillingId")?.setBillingId(id)
    }

    /** Call on sign-out: a new anonymous id, and the user id is cleared. */
    @JvmStatic
    public fun reset() {
        client("reset")?.reset()
    }

    /**
     * Call when the person deletes their account: Omni deletes everything it has about them (their events, traits
     * and email, under this device's ids and their user id), then this device starts over as a new anonymous person.
     */
    @JvmStatic
    public fun deleteUser() {
        client("deleteUser")?.deleteUser()
    }

    // Experiments

    /**
     * The variant of experiment [key] this device gets, or null: the config isn't loaded yet, the experiment isn't
     * running, or this device isn't in its audience. On null, show what the app shows today.
     */
    @JvmStatic
    public fun variant(key: String): String? = client("variant")?.variant(key)

    /** The chosen variant's payload (copy, flags, product ids), or null exactly when [variant] is null. */
    @JvmStatic
    public fun payload(key: String): Map<String, Any?>? = client("payload")?.payload(key)

    /**
     * Fetches the experiment config, for screens shown right at first launch (onboarding). Returns once it's in or
     * after [timeoutMs]; true when a fresh config arrived. The device's last copy is used either way.
     */
    public suspend fun loadExperiments(timeoutMs: Long = 2000): Boolean = suspendCoroutine { cont ->
        val c = client("loadExperiments")
        if (c == null) cont.resume(false) else c.loadExperiments(timeoutMs) { cont.resume(it) }
    }

    /** [loadExperiments] for Java and callback code: [callback] runs on the main thread. */
    @JvmStatic
    @JvmOverloads
    public fun loadExperiments(timeoutMs: Long = 2000, callback: ExperimentsCallback) {
        val c = client("loadExperiments")
        val main = Handler(Looper.getMainLooper())
        if (c == null) {
            main.post { callback.onLoaded(false) }
            return
        }
        c.loadExperiments(timeoutMs) { fetched -> main.post { callback.onLoaded(fetched) } }
    }

    // Push

    /**
     * Reports this device's FCM token, so Omni can send it push. Call with `FirebaseMessaging.getInstance().token`
     * at launch and from `onNewToken`; it's sent only when it changed (and weekly).
     */
    @JvmStatic
    public fun setPushToken(token: String) {
        client("setPushToken")?.setPushToken(token)
    }

    /**
     * Shows an Omni push yourself, with its buttons, image and deep link. Call it first thing in your
     * `FirebaseMessagingService.onMessageReceived` with `message.data`, and turn on [Options.showsPushes] so Omni
     * sends you pushes with buttons.
     *
     * Returns false when the message isn't Omni's (handle it as before), true when it is (shown, or not when the
     * person hasn't allowed notifications).
     */
    @JvmStatic
    public fun showPush(context: Context, data: Map<String, String>): Boolean {
        val content = PushContent.from(data) ?: return false
        try {
            PushNotifier.show(context, content, currentClient()?.log ?: Log.off)
        } catch (e: Exception) {
            Log.warn("showPush: couldn't show push ${content.messageId}: $e")
        }
        return true
    }

    /**
     * Records that an Omni push was opened (`$push_opened`, with the button tapped, if any) and returns its deep link
     * (`omni_url`: the button's link, else the push's), or null when the push isn't Omni's or has no link. Activities
     * launched from a notification are handled automatically; call this from `onNewIntent` (with `intent.extras`) or
     * for notifications you build yourself.
     */
    @JvmStatic
    public fun trackPushOpened(extras: Bundle?): String? {
        extras ?: return null
        val opened = try {
            Push.opened { extras.getString(it) }
        } catch (_: RuntimeException) {
            null
        } ?: return null
        client("trackPushOpened")?.pushOpened(opened.messageId, opened.action)
        synchronized(this) { appContext }?.let { PushNotifier.dismissAfterButton(it, opened) }
        return opened.url
    }

    // Sending and privacy

    /** Sends queued events now. */
    @JvmStatic
    public fun flush() {
        client("flush")?.flush()
    }

    /** Stops collection and deletes queued events. Remembered across launches. Works before [configure] too. */
    @JvmStatic
    public fun optOut() {
        synchronized(this) {
            sharedClient?.optOut() ?: run { pendingOptOut = true }
        }
    }

    /** Resumes collection after [optOut]. */
    @JvmStatic
    public fun optIn() {
        synchronized(this) {
            sharedClient?.optIn() ?: run { pendingOptOut = false }
        }
    }

    @JvmStatic
    public val isOptedOut: Boolean
        get() = synchronized(this) { sharedClient?.isOptedOut ?: (pendingOptOut == true) }

    /** The random id Omni knows this device by until [identify]. New after [reset]. Null before [configure]. */
    @JvmStatic
    public val anonymousId: String?
        get() = currentClient()?.anonymousId

    // Internals

    private var sharedClient: OmniClient? = null
    private var appContext: Context? = null
    private var pendingOptOut: Boolean? = null
    private var warnedUnconfigured = false

    private fun currentClient(): OmniClient? = synchronized(this) { sharedClient }

    private fun client(call: String): OmniClient? = synchronized(this) {
        sharedClient ?: run {
            if (!warnedUnconfigured) {
                warnedUnconfigured = true
                Log.warn("$call called before Omni.configure(context, key); ignored.")
            }
            null
        }
    }
}
