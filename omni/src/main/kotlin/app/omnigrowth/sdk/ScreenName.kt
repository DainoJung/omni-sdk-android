package app.omnigrowth.sdk

/**
 * Turns an activity's class name into a screen name, or null when it isn't a screen worth recording (system and
 * library activities, or a name we can't make sense of). Same rules as the Swift SDK's `ScreenName`.
 *
 * - `com.example.words.WordListActivity` → `WordList`
 * - `com.example.words.MainActivity` → `Main`
 * - `com.android.billingclient.api.ProxyBillingActivity`, `androidx.…`, anonymous classes → null
 */
internal object ScreenName {
    /** Packages whose activities are never the app's own screens (system UI, Play billing, Google sign-in, …). */
    val systemPackages = listOf(
        "android.", "androidx.", "com.android.", "com.google.android.", "com.google.firebase.",
        "dalvik.", "java.", "javax.", "kotlin.",
    )

    private val suffixes = listOf("Activity", "Fragment")

    fun from(className: String): String? {
        val name = className.trim()
        if (name.isEmpty() || systemPackages.any { name.startsWith(it) }) return null
        val simple = name.substringAfterLast('.').substringAfterLast('$')
        // Anonymous and synthetic classes (`Foo$1`, `_Generated`) say nothing about the screen.
        if (simple.isEmpty() || !simple[0].isLetter()) return null
        if (!simple.all { it.isLetterOrDigit() || it == '_' }) return null
        return strip(simple)
    }

    /** Removes the first matching suffix, unless that would leave nothing (a class named just `Activity`). */
    fun strip(name: String): String {
        for (suffix in suffixes) {
            if (name.endsWith(suffix)) return if (name == suffix) name else name.dropLast(suffix.length)
        }
        return name
    }
}

/**
 * Lets a screen through unless it's the same as the one just before it (an activity resuming after a dialog or the
 * notification shade isn't a new visit). Not thread-safe: the client guards it.
 */
internal class ScreenDeduper {
    var last: String? = null
        private set

    /** True when [name] should be sent; remembers it either way. */
    fun shouldSend(name: String): Boolean {
        val send = name != last
        last = name
        return send
    }

    /** An explicit `Omni.screen(...)` is always sent, but still counts as the last screen. */
    fun record(name: String) {
        last = name
    }

    fun clear() {
        last = null
    }
}
