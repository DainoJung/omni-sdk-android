# Omni SDK for Android

Send product events from your Android app to [Omni](https://omnigrowth.app), run in-app experiments, and report
push tokens and opens. It's small: no OkHttp, no coroutines library, no Firebase dependency. It queues events on the
device, sends them in batches and never blocks the main thread.

## Install

Add JitPack to your repositories in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Then add the dependency to your app module (use the latest tag):

```kotlin
dependencies {
    implementation("com.github.DainoJung:omni-sdk-android:0.1.0")
}
```

Requires Android 5.0 (API 21) or later.

## Quick start

Configure Omni once, in your `Application.onCreate`. Use your app's public key (`opk_…`), which Omni shows you.

```kotlin
import app.omnigrowth.sdk.Omni

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Omni.configure(this, "opk_…")
    }
}
```

(Register the class with `android:name=".MyApp"` on `<application>` in your manifest if you don't have one yet.)

Track what people do:

```kotlin
Omni.track("lesson_completed", mapOf("lesson" to 3, "duration_s" to 42))
Omni.screen("Paywall")
```

When someone signs in, tell Omni who they are. Pass `consent = true` only after they agreed to marketing email:

```kotlin
Omni.identify(user.id, mapOf("plan" to "free"))
Omni.setEmail(user.email, consent = user.agreedToMarketingEmail)
```

Link purchases to product events. With RevenueCat, pass its app user id. With Stripe, pass the customer id (`cus_…`):

```kotlin
Omni.setBillingId(Purchases.sharedInstance.appUserID)
```

On sign-out:

```kotlin
Omni.reset() // new anonymous id; the user id is cleared
```

Every call works from Java too (`Omni.track("signup")`, `Omni.loadExperiments(fetched -> …)`).

### Options

```kotlin
Omni.configure(this, "opk_…", Omni.Options(
    flushAt = 20,              // send when this many events are queued
    flushIntervalSeconds = 30, // …or at least this often
    maxQueue = 1000,           // most events kept while offline (oldest dropped first)
    captureScreens = true,     // $screen each time an activity resumes
    debug = false,             // log to Logcat (tag "Omni")
    captureLifecycle = true,   // $app_installed, $app_updated, $app_opened
))
```

## Screens

Omni records `$screen` each time an activity resumes, named from its class (`WordListActivity` → `WordList`,
`MainActivity` → `Main`), so you see the path people take without touching each screen. System and library activities
(Play billing, Google sign-in, `androidx.*`) are skipped, and the same screen twice in a row counts once. These events
carry `auto: true`.

Compose apps usually have one activity, so name their screens yourself where each destination is shown:

```kotlin
composable("paywall") {
    LaunchedEffect(Unit) { Omni.screen("Paywall") }
    PaywallScreen()
}
```

To turn automatic screens off: `Omni.Options(captureScreens = false)`.

## Deleting a person

When someone deletes their account, call `Omni.deleteUser()`. Omni deletes everything it has about them (their events,
traits and email), and this device starts over as a new anonymous person.

## Standard events

Omni reads a few events by name in every app: `signup`, `onboarding_step` (`step`, `name`), `onboarding_completed`,
`paywall_viewed` (`paywall`, `placement`), `purchase_started` (`product_id`, `paywall`), `share` and `invite_sent`. Send
them where they happen, plus your app's own core action. Easiest: ask Omni for the install prompt and paste it into
your coding agent; it places your app's whole tracking plan and keeps the rules in `AGENTS.md`.

## Experiments

Omni decides what runs; the SDK picks this device's variant on the device (the same person always gets the same
variant, even after `reset()`), so asking costs nothing:

```kotlin
val headline = Omni.payload("paywall_headline")?.get("headline") as? String
    ?: "Your current headline"
```

`Omni.variant(key)` returns the variant's key (`"control"`, `"b"`, …). Both return null when the config isn't loaded
yet, the experiment isn't running or this device isn't in it: then show what the app shows today. The first answer per
session reports an exposure, so Omni can compare variants.

The config is kept on the device and refreshed in the background. For a screen shown right at first launch
(onboarding), wait for it briefly:

```kotlin
lifecycleScope.launch {
    Omni.loadExperiments(timeoutMs = 2000) // returns when fetched, or after 2 s
    showOnboarding(Omni.variant("onboarding_v2"))
}
```

From Java or callback code: `Omni.loadExperiments(2000, fetched -> showOnboarding())` (runs on the main thread).

## Push notifications

Omni sends push through your own Firebase project; the SDK only reports the token, permission and opens. Report the
FCM token at launch and when it changes:

```kotlin
FirebaseMessaging.getInstance().token.addOnSuccessListener { Omni.setPushToken(it) }

class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = Omni.setPushToken(token)
}
```

It's sent only when it changed, and once a week. Whether notifications are allowed is read at launch and each time the
app comes to the foreground, and reported when it changes.

Omni's pushes carry the data keys `omni_message_id` and `omni_url` (an optional deep link). When someone taps one and
your app opens, Omni records the open by itself. If your activity is already running (`singleTop`) and gets the push in
`onNewIntent`, or you build notifications yourself, call:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    Omni.trackPushOpened(intent.extras)?.let { url -> openDeepLink(url) }
}
```

It returns the push's deep link, or null. An open is counted once per message.

## Where people came from

On first launch, Omni reads the Google Play Install Referrer once and sends `$attribution` with the campaign
(`utm_source`, `utm_campaign`, …) that brought the install. Omni's Play links carry `utm_campaign=<ct>`, so installs
join the post they came from. Without the Play Store (sideloaded, other stores) nothing is sent.

## What's collected

- The events you send, and their properties.
- With `captureLifecycle` on: app installed, app updated and app opened.
- With `captureScreens` on: the name of each activity as it resumes (from its class name, see Screens).
- Push: the FCM token, whether notifications are allowed, and which Omni pushes were opened.
- Once per install: the Play Install Referrer (the campaign of the link that led to the install).
- With every event: app version and build, platform (`android`), device type (phone or tablet), device model (e.g.
  `Pixel 9`), Android version, locale, a session id, a random anonymous id made on first launch, and your user id
  after `identify`.

What isn't collected: no advertising id (AAID/GAID) or Android id, no autocapture of taps, no screen contents,
screenshots or session recordings, no location, no contacts. Email and traits are sent only when you set them.

Queued events stay on the device until they're sent, and are retried with backoff when the network is down. The SDK's
files live in `noBackupFilesDir`, so they're never copied to another phone by Android backup. For the Play Console's
Data safety form: app interactions and in-app actions (analytics), device or other ids (the random anonymous id), and
email when you set it; all sent encrypted over HTTPS; deletable through `deleteUser()`.

## Opt-out

```kotlin
Omni.optOut()     // stops collection and deletes queued events; remembered across launches
Omni.optIn()      // resumes
Omni.isOptedOut   // current state
```

`optOut()` can be called before `configure`, so you can respect a choice made on a consent screen.

## License

MIT
