package dev.trrrst.watch.listen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Reads the phone's background settings and opens the pages to change them (PLAN
 *  Phase 3, Part 2). Only opens settings screens; it changes nothing by itself and
 *  needs no extra permission. The advice text is in [PhoneSetup]. */
object PhoneSettings {
    fun state(ctx: Context): PhoneSetup.State = PhoneSetup.State(
        batteryOptimised = runCatching {
            !ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrNull(),
        exactAlarms = ListenScheduler.exactAlarmsAllowed(ctx),
        isXiaomi = PhoneSetup.isXiaomi(Build.MANUFACTURER, Build.BRAND),
        bluetoothOn = runCatching {
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled
        }.getOrNull(),
    )

    /** Android's list of apps with battery optimisation (find the app, "Don't optimise"). */
    fun openBatteryOptimisation(ctx: Context) {
        if (!start(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) openAppInfo(ctx)
    }

    /** MIUI's Autostart list. Other phones (or if MIUI moved it): the app's info page. */
    fun openAutostart(ctx: Context) {
        val miui = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        if (!start(ctx, miui)) openAppInfo(ctx)
    }

    fun openAppInfo(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        // ActivityNotFoundException, or SecurityException if MIUI doesn't export the page.
        ListenLog(ctx).add("couldn't open ${i.component?.className ?: i.action}: $e")
        false
    }
}
