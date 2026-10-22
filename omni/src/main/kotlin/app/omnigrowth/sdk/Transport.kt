package app.omnigrowth.sdk

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal fun interface Cancellable {
    fun cancel()
}

/** Where the SDK's state lives: one serial background thread, so nothing needs a lock but identity. */
internal interface Worker {
    fun post(task: () -> Unit)
    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable
}

/** The production [Worker]: a single daemon thread. A failing task is logged, never thrown into the app. */
internal class ExecutorWorker(name: String) : Worker {
    private val executor = ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, name).apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    override fun post(task: () -> Unit) {
        executor.execute { guarded(task) }
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable {
        val future: ScheduledFuture<*> = executor.schedule({ guarded(task) }, delayMs, TimeUnit.MILLISECONDS)
        return Cancellable { future.cancel(false) }
    }
}

internal inline fun guarded(task: () -> Unit) {
    try {
        task()
    } catch (e: Exception) {
        Log.warn("internal error (please report): $e")
    } catch (e: LinkageError) {
        Log.warn("internal error (please report): $e")
    }
}

internal class HttpRequest(
    val method: String,
    val url: String,
    val body: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** `status` null means no response came back (network error, timeout); then `error` says why. */
internal class HttpResponse(val status: Int?, val body: String? = null, val error: Throwable? = null)

/** Sends one request. `completion` may run on any thread. */
internal interface Transport {
    fun send(request: HttpRequest, completion: (HttpResponse) -> Unit)
}

/**
 * HttpURLConnection on its own thread, so a slow network never holds up `track` or the experiment timeout
 * (the same split as URLSession and the serial queue in the Swift SDK).
 */
internal class HttpUrlConnectionTransport : Transport {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "omni-network").apply { isDaemon = true }
    }

    override fun send(request: HttpRequest, completion: (HttpResponse) -> Unit) {
        executor.execute {
            val response = perform(request)
            guarded { completion(response) }
        }
    }

    private fun perform(request: HttpRequest): HttpResponse {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = 15_000
                readTimeout = 30_000
                useCaches = false
                instanceFollowRedirects = true
                request.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val body = if (status in 200..299) connection.inputStream.use { readCapped(it) } else {
                connection.errorStream?.use { readCapped(it) }
                null
            }
            HttpResponse(status, body)
        } catch (e: IOException) {
            HttpResponse(null, error = e)
        } catch (e: RuntimeException) {
            HttpResponse(null, error = e)
        } finally {
            connection?.disconnect()
        }
    }

    /** Responses are small (`{"ok":true}`, a config ≤ 64 KB); anything past 1 MB isn't read. */
    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < 1_000_000) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toString("UTF-8")
    }
}

/** What to do with a batch after a send. Pure, so it's tested on its own. */
internal object RetryPolicy {
    enum class Action {
        /** Delivered: remove the events. */
        SUCCESS,

        /** The server won't ever take it (bad body / bad key): remove the events, don't retry. */
        DROP,

        /** Too large: send fewer events at a time. */
        SPLIT,

        /** Keep the events and try again later with backoff. */
        RETRY,
    }

    const val MIN_BACKOFF_MS = 5_000L
    const val MAX_BACKOFF_MS = 300_000L

    /** `status` null means no response (network error, timeout). */
    fun action(status: Int?): Action = when (status) {
        null -> Action.RETRY
        in 200..299 -> Action.SUCCESS
        400, 401 -> Action.DROP
        413 -> Action.SPLIT
        408, 429 -> Action.RETRY
        in 500..599 -> Action.RETRY
        // Other 4xx (403, 404, 422…) won't succeed on retry either.
        in 402..499 -> Action.DROP
        else -> Action.RETRY
    }

    /** Delay before retry number [attempt] (1-based): 5 s, 10 s, 20 s … capped at 5 min. */
    fun backoffMs(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 16)
        return (MIN_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
    }
}
