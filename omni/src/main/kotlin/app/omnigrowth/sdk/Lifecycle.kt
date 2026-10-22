package app.omnigrowth.sdk

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Tells the client when the app comes to the foreground or goes to the background (ProcessLifecycleOwner, which
 * counts every activity and ignores rotations), records screens as activities resume, and notices activities opened
 * from an Omni push.
 *
 * Always installed: flushing on background and session timing don't depend on `captureLifecycle`;
 * the client decides whether to emit `$app_opened`.
 */
internal class AndroidHooks(
    private val client: OmniClient,
) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        val register = Runnable {
            try {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            } catch (e: Exception) {
                Log.warn("couldn't watch the app's foreground/background: $e")
            }
        }
        // Lifecycle observers must be added on the main thread.
        if (Looper.myLooper() == Looper.getMainLooper()) register.run() else Handler(Looper.getMainLooper()).post(register)
    }

    override fun onStart(owner: LifecycleOwner) {
        client.appDidBecomeActive()
    }

    override fun onStop(owner: LifecycleOwner) {
        client.appDidEnterBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Restored after the process died: its launch intent was handled in the previous life.
        if (savedInstanceState != null) markHandled(activity.intent)
    }

    override fun onActivityResumed(activity: Activity) {
        handleLaunchIntent(activity.intent)
        ScreenName.from(activity.javaClass.name)?.let { client.observedScreen(it, auto = true) }
    }

    /** `$push_opened` for an activity opened from an Omni push, once per intent. */
    private fun handleLaunchIntent(intent: Intent?) {
        intent ?: return
        try {
            if (intent.getBooleanExtra(HANDLED_EXTRA, false)) return
            // Reopened from recents: the same old intent comes back, but no push was tapped.
            if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
            val opened = Push.opened { intent.getStringExtra(it) } ?: return
            intent.putExtra(HANDLED_EXTRA, true)
            client.pushOpened(opened.messageId)
        } catch (_: RuntimeException) {
            // Extras the app's classloader can't unparcel (BadParcelableException): not ours to read.
        }
    }

    private fun markHandled(intent: Intent?) {
        try {
            intent?.putExtra(HANDLED_EXTRA, true)
        } catch (_: RuntimeException) {
        }
    }

    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}

    companion object {
        const val HANDLED_EXTRA = "app.omnigrowth.sdk.push_handled"
    }
}
