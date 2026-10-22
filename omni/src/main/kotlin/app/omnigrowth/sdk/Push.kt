package app.omnigrowth.sdk

/** When to report the push token, and how to read Omni's push payload. Pure, so it's tested on its own. */
internal object Push {
    const val PROVIDER = "fcm"

    /** FCM has no sandbox: every Android token is a production one. */
    const val ENVIRONMENT = "production"

    /** A token is reported again a week after the last report, so Omni knows it's still live. */
    const val RESEND_MS = 7L * 24 * 60 * 60 * 1000

    /** FCM data keys on Omni's pushes. */
    const val MESSAGE_ID_KEY = "omni_message_id"
    const val URL_KEY = "omni_url"

    fun shouldSendToken(token: String, lastToken: String?, lastSentAt: Long?, now: Long): Boolean =
        token != lastToken || lastSentAt == null || now - lastSentAt >= RESEND_MS || now < lastSentAt

    fun tokenProperties(token: String): Map<String, Any?> = linkedMapOf(
        "token" to token,
        "provider" to PROVIDER,
        "environment" to ENVIRONMENT,
    )

    class Opened(val messageId: String, val url: String?)

    /** Omni's push from its data (`get` reads one key), or null when the push isn't Omni's. */
    fun opened(get: (String) -> String?): Opened? {
        val id = get(MESSAGE_ID_KEY)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = get(URL_KEY)?.trim()?.takeIf { it.isNotEmpty() }
        return Opened(id, url)
    }
}
