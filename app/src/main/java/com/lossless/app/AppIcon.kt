package com.lossless.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The home-screen icon choices. Each is an `<activity-alias>` in the manifest
 * carrying the launcher intent filter; exactly one is enabled at a time.
 *
 * Two, since Lossless has one mark. [Default] is the adaptive icon, which is
 * itself two drawings rather than two choices: `drawable/ic_launcher_foreground`
 * is a black mark on a near-white tile and `drawable-night/` the reverse, so the
 * launcher picks whichever suits the system theme without the listener choosing
 * anything. [Monochrome] opts into a themed icon — one flat colour the system
 * tints from the wallpaper, for launchers that offer that.
 *
 * This list used to be twenty-two entries: glass and neon treatments of the old
 * mark, each a set of hand-cut foregrounds per device family. One mark makes
 * that a menu of the same drawing in different lighting, so it is gone.
 */
enum class AppIcon(val alias: String, val iconRes: Int, val labelRes: Int) {
    Default("LauncherDefault", R.mipmap.ic_launcher, R.string.app_icon_default),
    Monochrome("LauncherMonochrome", R.mipmap.ic_monochrome, R.string.app_icon_monochrome);

    private fun component(context: Context) =
        ComponentName(context.packageName, "com.lossless.app.$alias")

    companion object {
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: Default
        }

        /** Enables [icon] before disabling the rest so there is never a moment with no launcher entry. */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
