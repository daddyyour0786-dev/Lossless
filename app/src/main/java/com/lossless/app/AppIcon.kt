package com.lossless.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The home-screen icon choices. Each is an `<activity-alias>` in the manifest
 * carrying the launcher intent filter; exactly one is enabled at a time.
 *
 * Every entry is the same mark; what changes is the light it is seen in — the
 * tile it sits on and the colour it is drawn in. All of them are adaptive icons,
 * so a launcher masks them uniformly and none of them reads as a different app.
 *
 * [Default] follows the system theme, because its foreground and tile both have
 * a `-night` variant: `drawable/ic_launcher_foreground` is a black mark on a
 * near-white tile and `drawable-night/` the reverse. The rest are fixed, which
 * is the point of offering them — a listener who wants the dark tile on a light
 * phone can have it. [Monochrome] opts into a themed icon, one flat colour the
 * system tints from the wallpaper.
 *
 * Adding one means a resource (see `tools/generate_icons.py`, `VARIANTS`), an
 * `<activity-alias>` in the manifest, and an entry here. Nothing else.
 */
enum class AppIcon(val alias: String, val iconRes: Int, val labelRes: Int) {
    Default("LauncherDefault", R.mipmap.ic_launcher, R.string.app_icon_default),
    Dark("LauncherDark", R.mipmap.ic_launcher_dark, R.string.app_icon_dark),
    Graphite("LauncherGraphite", R.mipmap.ic_launcher_graphite, R.string.app_icon_graphite),
    GlassLight("LauncherGlassLight", R.mipmap.ic_launcher_glass_light, R.string.app_icon_glass_light),
    GlassDark("LauncherGlassDark", R.mipmap.ic_launcher_glass_dark, R.string.app_icon_glass_dark),
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
