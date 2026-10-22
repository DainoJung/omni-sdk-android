package app.omnigrowth.sdk

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import java.util.Locale

/** The static part of `context`, collected once at configure. */
internal data class DeviceInfo(
    val appVersion: String? = null,
    val build: String? = null,
    val platform: String = "android",
    val deviceType: String = "phone",
    val locale: String? = null,
    val osVersion: String? = null,
    val deviceModel: String? = null,
    /**
     * True when this version is the one first installed (never updated since), false when the app was updated in
     * between, null when unknown. Tells a real first launch from the first launch of the version that added Omni.
     */
    val isFreshInstall: Boolean? = null,
) {
    companion object {
        fun collect(context: Context): DeviceInfo {
            val info: PackageInfo? = try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: Exception) {
                null
            }
            val build = info?.let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toString() else @Suppress("DEPRECATION") it.versionCode.toString()
            }
            return DeviceInfo(
                appVersion = info?.versionName,
                build = build,
                deviceType = deviceType(context.resources.configuration.smallestScreenWidthDp),
                locale = localeTag(Locale.getDefault()),
                osVersion = Build.VERSION.RELEASE,
                deviceModel = Build.MODEL?.takeIf { it.isNotBlank() },
                isFreshInstall = info?.let { it.firstInstallTime > 0 && it.firstInstallTime == it.lastUpdateTime },
            )
        }

        /** Android's own line between phones and tablets: 600 dp on the shortest side. */
        fun deviceType(smallestScreenWidthDp: Int): String = if (smallestScreenWidthDp >= 600) "tablet" else "phone"

        /** `ko-KR`, `zh-Hans-CN`; extensions (`-u-ca-japanese`) dropped; null when undetermined. */
        fun localeTag(locale: Locale): String? {
            val tag = locale.toLanguageTag()
            val cut = Regex("-[a-zA-Z0-9]-").find(tag)?.range?.first ?: tag.length
            return tag.substring(0, cut).takeUnless { it.isEmpty() || it == "und" }
        }
    }
}
