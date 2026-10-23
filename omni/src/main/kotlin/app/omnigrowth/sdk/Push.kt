package app.omnigrowth.sdk

/** When to report the push token, and how to read Omni's push payload. Pure, so it's tested on its own. */
internal object Push {
    const val PROVIDER = "fcm"

    /** FCM has no sandbox: every Android token is a production one. */
    const val ENVIRONMENT = "production"

    /** A token is reported again a week after the last report, so Omni knows it's still live. */
    const val RESEND_MS = 7L * 24 * 60 * 60 * 1000

    /** FCM data keys on Omni's pushes (and extras on the intents of the notifications the SDK builds). */
    const val MESSAGE_ID_KEY = "omni_message_id"
    const val URL_KEY = "omni_url"
    const val ACTION_KEY = "omni_action"

    /** `action` of an open of the notification itself, not one of its buttons. */
    const val OPEN_ACTION = "open"

    fun shouldSendToken(token: String, lastToken: String?, lastSentAt: Long?, now: Long): Boolean =
        token != lastToken || lastSentAt == null || now - lastSentAt >= RESEND_MS || now < lastSentAt

    /** [buttons]: the app shows Omni's pushes itself ([Omni.showPush]), so Omni may send them with buttons. */
    fun tokenProperties(token: String, buttons: Boolean = false): Map<String, Any?> = linkedMapOf<String, Any?>(
        "token" to token,
        "provider" to PROVIDER,
        "environment" to ENVIRONMENT,
    ).apply { if (buttons) put("buttons", true) }

    class Opened(val messageId: String, val url: String?, val action: String)

    /** Omni's push from its data (`get` reads one key), or null when the push isn't Omni's. */
    fun opened(get: (String) -> String?): Opened? {
        val id = get(MESSAGE_ID_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = get(URL_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        val action = get(ACTION_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: OPEN_ACTION
        return Opened(id, url, action)
    }
}

/** One button of a push: from `omni_actions` in the push, or a category in the config. */
internal class PushAction(val id: String, val title: String, val url: String?, val foreground: Boolean = true) {
    companion object {
        /** A button from its JSON, or null when it has no id or title. `open` is the notification's own action, not a button. */
        fun from(m: Map<*, *>): PushAction? {
            val id = (m["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it != Push.OPEN_ACTION } ?: return null
            val title = (m["title"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val url = (m["url"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            return PushAction(id, title, url, m["foreground"] as? Boolean ?: true)
        }

        /** Up to [PushContent.MAX_ACTIONS] usable buttons, in order; unreadable ones and repeated ids are skipped. */
        fun list(raw: List<*>?): List<PushAction> {
            val out = ArrayList<PushAction>()
            for (item in raw.orEmpty()) {
                val action = (item as? Map<*, *>)?.let(::from) ?: continue
                if (out.any { it.id == action.id }) continue
                out.add(action)
                if (out.size == PushContent.MAX_ACTIONS) break
            }
            return out
        }
    }
}

/** `push_categories` in the config: the buttons a push may use, defined ahead of time (PROTOCOL.md "Push buttons"). */
internal class PushCategory(val id: String, val actions: List<PushAction>) {
    companion object {
        /** Lenient: anything that isn't a list is no categories, and a category without an id is skipped. */
        fun list(raw: Any?): List<PushCategory> = (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val id = (m["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            PushCategory(id, PushAction.list(m["actions"] as? List<*>))
        }
    }
}

/**
 * What a data-only Omni push asks to show, read from its FCM data. Plain Kotlin, so the mapping is tested without a
 * device; [PushNotifier] turns it into a notification.
 */
internal class PushContent(
    val messageId: String,
    val title: String?,
    /** The subtitle (if any) as the first line, then the body: Android has no separate subtitle line. */
    val body: String?,
    val url: String?,
    /** http(s) only; anything else is shown as text. */
    val imageUrl: String?,
    val channel: String?,
    val group: String?,
    val actions: List<PushAction>,
) {
    /** The same push always gets the same id, so a repeat replaces it and opening a button can dismiss it. */
    val notificationId: Int get() = messageId.hashCode()

    /** Extras of the intent that opens the app: for the notification itself ([action] null) or one of its buttons. */
    fun openExtras(action: PushAction?): Map<String, String> = linkedMapOf<String, String>().apply {
        put(Push.MESSAGE_ID_KEY, messageId)
        put(Push.ACTION_KEY, action?.id ?: Push.OPEN_ACTION)
        // A button without its own link opens what the notification opens.
        (action?.url ?: url)?.let { put(Push.URL_KEY, it) }
    }

    /** Different per notification and button: PendingIntents whose intents differ only in extras would be merged. */
    fun requestCode(action: PushAction?): Int = "$messageId:${action?.id ?: Push.OPEN_ACTION}".hashCode()

    companion object {
        const val MAX_ACTIONS = 3
        const val TITLE_KEY = "omni_title"
        const val BODY_KEY = "omni_body"
        const val SUBTITLE_KEY = "omni_subtitle"
        const val IMAGE_KEY = "omni_image"
        const val CHANNEL_KEY = "omni_channel"
        const val GROUP_KEY = "omni_group"
        const val ACTIONS_KEY = "omni_actions"

        /** The push to show, or null when the data isn't Omni's (no `omni_message_id`). */
        fun from(data: Map<String, String?>): PushContent? {
            fun text(key: String) = data[key]?.trim()?.takeIf { it.isNotEmpty() }
            val id = text(Push.MESSAGE_ID_KEY) ?: return null
            val body = listOfNotNull(text(SUBTITLE_KEY), text(BODY_KEY)).joinToString("\n").ifEmpty { null }
            val image = text(IMAGE_KEY)?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            val actions = text(ACTIONS_KEY)?.let { Json.parse(it) as? List<*> }
            return PushContent(
                messageId = id,
                title = text(TITLE_KEY),
                body = body,
                url = text(Push.URL_KEY),
                imageUrl = image,
                channel = text(CHANNEL_KEY),
                group = text(GROUP_KEY),
                actions = PushAction.list(actions),
            )
        }
    }
}
