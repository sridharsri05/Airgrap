package com.airgrab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Getting permission to keep running.
 *
 * A foreground service is a request, not a guarantee. Android's own battery
 * optimiser will doze an app it considers idle, and manufacturer software goes
 * considerably further: vivo, Xiaomi, Oppo, Huawei and Samsung all ship their
 * own process killers that ignore foreground status entirely.
 *
 * This matters more here than for most apps. A messaging app that is killed
 * catches up when it reopens; AirGrab killed is a phone that has silently
 * stopped existing as far as the desktop is concerned, and the user finds out
 * by making a gesture that does nothing.
 *
 * None of this can be fixed in code. It needs the user to allow the app once,
 * in a screen only they can reach — so the honest thing is to detect the
 * situation, explain it plainly, and take them there.
 *
 * The manufacturer screens below are undocumented and change between
 * firmware versions, so every one of them is tried and allowed to fail. A
 * missing screen falls back to the app's own settings page, which always
 * exists.
 */
object BatteryPolicy {

    /** True when Android's own optimiser has been told to leave this app alone. */
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /**
     * The system dialog asking for that exemption.
     *
     * Uses ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which shows a single
     * prompt, rather than dumping the user into a list of every installed app
     * and hoping they find this one.
     */
    fun requestExemption(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** True on a manufacturer known to kill background apps beyond Android's rules. */
    fun needsManufacturerAllowance(): Boolean =
        Build.MANUFACTURER.lowercase() in setOf(
            "vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "realme",
            "oneplus", "huawei", "honor", "samsung", "meizu", "asus",
        )

    /**
     * Plain-language instructions, because the setting is never where the user
     * would look for it and is rarely called anything sensible.
     */
    fun instructions(): String = when (Build.MANUFACTURER.lowercase()) {
        "vivo", "iqoo" -> """
            On this phone the setting lives in two places, and both matter:

            1. Settings > Battery > Background power consumption management >
               AirGrab > Allow high background power consumption
            2. Settings > Apps > Special app access > Autostart > turn on AirGrab

            Without these, the phone stops AirGrab after a few minutes with the
            screen off, and your PC sees it disappear.
        """.trimIndent()

        "xiaomi", "redmi", "poco" -> """
            1. Settings > Apps > Manage apps > AirGrab > Autostart: on
            2. In Recents, pull down on AirGrab and tap the padlock
            3. Settings > Apps > Manage apps > AirGrab > Battery saver: No
               restrictions
        """.trimIndent()

        "oppo", "realme", "oneplus" -> """
            1. Settings > Battery > Background power consumption > allow AirGrab
            2. Settings > Apps > AirGrab > Allow auto-launch
        """.trimIndent()

        "huawei", "honor" -> """
            Settings > Battery > App launch > AirGrab > Manage manually, then
            turn on all three: Auto-launch, Secondary launch, Run in background.
        """.trimIndent()

        "samsung" -> """
            Settings > Battery > Background usage limits > make sure AirGrab is
            NOT in "Sleeping apps" or "Deep sleeping apps".
        """.trimIndent()

        else -> """
            Allow AirGrab to run in the background in your phone's battery
            settings, so it stays reachable while you are using other apps.
        """.trimIndent()
    }

    /**
     * The manufacturer's own background-app screen, or null if none is known.
     *
     * Every component name here is undocumented and may vanish in a firmware
     * update, so callers must be ready for the intent to fail to resolve and
     * fall back to [appSettings].
     */
    fun manufacturerSettings(): Intent? {
        val components = when (Build.MANUFACTURER.lowercase()) {
            "vivo", "iqoo" -> listOf(
                "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
                "com.iqoo.secure/.safeguard.PurviewTabActivity",
            )
            "xiaomi", "redmi", "poco" -> listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            "oppo", "realme" -> listOf(
                "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
                "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            )
            "oneplus" -> listOf(
                "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            )
            "huawei", "honor" -> listOf(
                "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
                "com.huawei.systemmanager/.optimize.process.ProtectActivity",
            )
            else -> emptyList()
        }

        return components.firstNotNullOfOrNull { spec ->
            val (pkg, cls) = spec.split("/")
            val component = ComponentName(pkg, if (cls.startsWith(".")) pkg + cls else cls)
            Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** This app's own settings page. Always present, so always a safe fallback. */
    fun appSettings(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True when the intent will actually open something on this device. */
    fun canOpen(context: Context, intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null
}
