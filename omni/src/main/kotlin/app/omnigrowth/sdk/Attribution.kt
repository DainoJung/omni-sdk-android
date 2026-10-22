package app.omnigrowth.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicBoolean

/** Reading the Play Install Referrer: where this install came from (PROTOCOL.md "Attribution"). */
internal object Attribution {
    const val SOURCE = "play_install_referrer"

    /** Tries on this many launches when the Play Store can't answer, then gives up. */
    const val MAX_ATTEMPTS = 3

    private val utmKeys = listOf("utm_source", "utm_medium", "utm_campaign", "utm_content", "utm_term")

    sealed class Result {
        class Found(val referrer: String, val clickTsSeconds: Long, val installTsSeconds: Long) : Result()

        /** No Play Store (or it never will answer on this device): nothing to send, don't ask again. */
        object NotAvailable : Result()

        /** The Play Store didn't answer this time. */
        object TryLater : Result()
    }

    /** `utm_source=google-play&utm_medium=organic` → its decoded key/values (the first of each key). */
    fun parse(referrer: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in referrer.split('&')) {
            if (pair.isEmpty()) continue
            val key = decode(pair.substringBefore('='))
            val value = if ('=' in pair) decode(pair.substringAfter('=')) else ""
            if (key.isNotEmpty() && key !in out) out[key] = value
        }
        return out
    }

    /** `$attribution`'s properties. Missing utm values and zero timestamps are left out. */
    fun properties(found: Result.Found): Map<String, Any?> {
        val params = parse(found.referrer)
        return LinkedHashMap<String, Any?>().apply {
            put("source", SOURCE)
            put("referrer", found.referrer)
            for (k in utmKeys) params[k]?.takeIf { it.isNotEmpty() }?.let { put(k, it) }
            if (found.clickTsSeconds > 0) put("click_ts", Timestamp.format(found.clickTsSeconds * 1000))
            if (found.installTsSeconds > 0) put("install_ts", Timestamp.format(found.installTsSeconds * 1000))
        }
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: IllegalArgumentException) {
        s
    }

    /**
     * Asks the Play Store once. [background] runs the blocking read off the main thread; [done] gets the result
     * exactly once. Any failure (no Play Store, an old one, a sandboxed device) ends as a [Result], never a crash.
     */
    fun read(context: Context, background: (() -> Unit) -> Unit, done: (Result) -> Unit) {
        val finished = AtomicBoolean(false)
        var client: InstallReferrerClient? = null
        fun finish(result: Result) {
            if (!finished.compareAndSet(false, true)) return
            try {
                client?.endConnection()
            } catch (_: Throwable) {
            }
            done(result)
        }
        Handler(Looper.getMainLooper()).post {
            try {
                val c = InstallReferrerClient.newBuilder(context).build()
                client = c
                c.startConnection(object : InstallReferrerStateListener {
                    override fun onInstallReferrerSetupFinished(responseCode: Int) {
                        when (responseCode) {
                            InstallReferrerClient.InstallReferrerResponse.OK -> background {
                                try {
                                    val details = c.installReferrer
                                    finish(
                                        Result.Found(
                                            details.installReferrer.orEmpty(),
                                            details.referrerClickTimestampSeconds,
                                            details.installBeginTimestampSeconds,
                                        ),
                                    )
                                } catch (_: Exception) {
                                    finish(Result.TryLater)
                                }
                            }
                            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                            InstallReferrerClient.InstallReferrerResponse.DEVELOPER_ERROR,
                            InstallReferrerClient.InstallReferrerResponse.PERMISSION_ERROR,
                            -> finish(Result.NotAvailable)
                            else -> finish(Result.TryLater)
                        }
                    }

                    override fun onInstallReferrerServiceDisconnected() {
                        finish(Result.TryLater)
                    }
                })
            } catch (_: Throwable) {
                // SecurityException on some devices, or the library stripped from the app.
                finish(Result.NotAvailable)
            }
        }
    }
}
