package app.omnigrowth.sdk

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Builds and posts the notification for a data-only Omni push ([Omni.showPush]), the way FCM would for its own
 * notification messages, plus Omni's buttons (PROTOCOL.md "Push buttons").
 */
internal object PushNotifier {
    /** Created once, on first use, when the push names no channel the app has. */
    const val DEFAULT_CHANNEL_ID = "omni_notifications"
    const val DEFAULT_CHANNEL_NAME = "Notifications"

    /** Every notification the SDK posts carries this tag, so its ids never collide with the app's own. */
    const val TAG = "omni"

    /** The same meta-data FCM reads, so the app's icon and channel for FCM notifications apply here too. */
    private const val ICON_META = "com.google.firebase.messaging.default_notification_icon"
    private const val CHANNEL_META = "com.google.firebase.messaging.default_notification_channel_id"

    /** FCM leaves `onMessageReceived` about 10 s; a slow image falls back to text well within that. */
    const val IMAGE_TIMEOUT_MS = 4_000
    const val IMAGE_MAX_BYTES = 1_048_576
    const val IMAGE_MAX_SIDE = 1440

    fun show(context: Context, content: PushContent, log: Log) {
        val app = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            log("showPush: POST_NOTIFICATIONS isn't granted, so push ${content.messageId} isn't shown")
            return
        }
        if (content.imageUrl != null && Looper.myLooper() == Looper.getMainLooper()) {
            // Never download on the main thread. (FCM calls onMessageReceived on a background thread, where waiting
            // for the image also keeps the process alive until the notification is posted.)
            Thread({ guarded { post(app, content, log) } }, "omni-push").start()
        } else {
            post(app, content, log)
        }
    }

    /** Tapping a button doesn't dismiss its notification by itself (only the notification's own tap does). */
    fun dismissAfterButton(context: Context, opened: Push.Opened) {
        if (opened.action == Push.OPEN_ACTION) return
        try {
            NotificationManagerCompat.from(context).cancel(TAG, opened.messageId.hashCode())
        } catch (_: RuntimeException) {
        }
    }

    @SuppressLint("MissingPermission") // checked in show(); a SecurityException is still caught
    private fun post(context: Context, content: PushContent, log: Log) {
        val manager = NotificationManagerCompat.from(context)
        val meta = metaData(context)
        val builder = NotificationCompat.Builder(context, channel(manager, content.channel, meta))
            .setSmallIcon(smallIcon(context, meta))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        content.title?.let { builder.setContentTitle(it) }
        content.body?.let { builder.setContentText(it) }
        content.group?.let { builder.setGroup(it) }

        val picture = content.imageUrl?.let { download(it, log) }
        if (picture != null) {
            builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).setSummaryText(content.body))
        } else if (content.body != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
        }

        val open = openIntent(context, content, null)
        if (open != null) {
            builder.setContentIntent(open)
            for (action in content.actions) {
                val intent = openIntent(context, content, action) ?: continue
                builder.addAction(NotificationCompat.Action.Builder(0, action.title, intent).build())
            }
        } else {
            log("showPush: the app has no launch activity, so push ${content.messageId} opens nothing")
        }

        try {
            manager.notify(TAG, content.notificationId, builder.build())
            log("showPush: posted ${content.messageId} (${content.actions.size} button(s))")
        } catch (e: SecurityException) {
            log("showPush: not allowed to post: ${e.message}")
        }
    }

    /** The app's launch activity with Omni's open extras; [action] null is the notification itself. */
    private fun openIntent(context: Context, content: PushContent, action: PushAction?): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        // An app already showing its launch activity gets the open in onNewIntent rather than a second copy of it.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        for ((k, v) in content.openExtras(action)) intent.putExtra(k, v)
        return PendingIntentCompat.getActivity(
            context, content.requestCode(action), intent, PendingIntent.FLAG_UPDATE_CURRENT, false,
        )
    }

    /** The push's channel when the app has it, else the app's FCM default channel, else the SDK's own. */
    private fun channel(manager: NotificationManagerCompat, requested: String?, meta: Bundle?): String {
        if (Build.VERSION.SDK_INT < 26) return requested ?: DEFAULT_CHANNEL_ID // no channels yet: any id works
        val exists = { id: String -> manager.getNotificationChannelCompat(id) != null }
        requested?.takeIf(exists)?.let { return it }
        meta?.getString(CHANNEL_META)?.takeIf { it.isNotEmpty() && exists(it) }?.let { return it }
        if (!exists(DEFAULT_CHANNEL_ID)) {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(DEFAULT_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(DEFAULT_CHANNEL_NAME)
                    .build(),
            )
        }
        return DEFAULT_CHANNEL_ID
    }

    /** A notification without a valid small icon throws on post, so there's always one. */
    private fun smallIcon(context: Context, meta: Bundle?): Int {
        val fromManifest = meta?.getInt(ICON_META, 0) ?: 0
        if (fromManifest != 0) return fromManifest
        return context.applicationInfo?.icon?.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon
    }

    private fun metaData(context: Context): Bundle? = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
    } catch (_: Exception) {
        null
    }

    /** The image, or null (the push is then shown as text): slow, too large, not an image. */
    private fun download(url: String, log: Log): Bitmap? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = IMAGE_TIMEOUT_MS
                readTimeout = IMAGE_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            if (connection.responseCode !in 200..299) {
                log("showPush: image HTTP ${connection.responseCode}; showing text")
                return null
            }
            val bytes = connection.inputStream.use { readUpTo(it, IMAGE_MAX_BYTES + 1) }
            if (bytes.size > IMAGE_MAX_BYTES) {
                log("showPush: image larger than 1 MB; showing text")
                return null
            }
            decode(bytes)
        } catch (e: IOException) {
            log("showPush: image failed (${e.message}); showing text")
            null
        } catch (e: RuntimeException) {
            log("showPush: image failed (${e.message}); showing text")
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readUpTo(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /** A power of two that brings the longer side to at most [IMAGE_MAX_SIDE]: a 1 MB JPEG can be a 48 MB bitmap. */
    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > IMAGE_MAX_SIDE) sample *= 2
        return sample
    }
}
