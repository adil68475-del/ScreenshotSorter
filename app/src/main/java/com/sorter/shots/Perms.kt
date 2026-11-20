package com.sorter.shots

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings

object Perms {
    fun overlay(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)
    fun files(): Boolean = Environment.isExternalStorageManager()

    fun battery(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun notif(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // The two permissions the app cannot work without.
    fun core(ctx: Context): Boolean = overlay(ctx) && files()

    fun openOverlay(act: Activity) {
        act.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${act.packageName}")))
    }

    fun openFiles(act: Activity) {
        act.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${act.packageName}"))
        )
    }

    fun openBattery(act: Activity) {
        try {
            act.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${act.packageName}"))
            )
        } catch (e: Exception) {
            act.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun askNotif(act: Activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            act.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // Auto-start / protected-apps screens of common phone brands (tries each, falls back to app info).
    private val AUTOSTART = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",
        "com.itel.autobootmanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity"
    )

    fun openAutoStart(act: Activity) {
        for ((pkg, cls) in AUTOSTART) {
            try {
                val i = Intent()
                i.component = ComponentName(pkg, cls)
                act.startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        openAppInfo(act)
    }

    fun openAppInfo(act: Activity) {
        act.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${act.packageName}"))
        )
    }
}

// Keeps the watcher alive: restarts it if the phone or a cleaner closed it.
object Watchdog {
    @Suppress("DEPRECATION")
    fun running(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java)
        return am.getRunningServices(100).any { it.service.className == WatcherService::class.java.name }
    }

    fun ensure(ctx: Context) {
        if (!Perms.core(ctx) || running(ctx)) return
        try {
            ctx.startForegroundService(Intent(ctx, WatcherService::class.java))
        } catch (_: Exception) {
        }
    }

    // Repeating check (every ~15 min). Survives app kills and reboots.
    fun schedule(ctx: Context) {
        schedulePeriodic(ctx)
        scheduleTrigger(ctx)
    }

    // Wakes the app the moment any new picture/video is saved (works even if the service was killed).
    fun scheduleTrigger(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(3) == null) {
                val b = JobInfo.Builder(3, ComponentName(ctx, WatchdogJob::class.java))
                b.addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                b.addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                    )
                )
                b.setTriggerContentUpdateDelay(300L)
                b.setTriggerContentMaxDelay(1500L)
                js.schedule(b.build())
            }
        } catch (_: Exception) {
        }
    }

    private fun schedulePeriodic(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(1) == null) {
                val info = JobInfo.Builder(1, ComponentName(ctx, WatchdogJob::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                js.schedule(info)
            }
        } catch (_: Exception) {
        }
    }

    // One quick check a few seconds from now (used when the app is swiped away).
    fun kick(ctx: Context) {
        try {
            val js = ctx.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(2, ComponentName(ctx, WatchdogJob::class.java))
                .setMinimumLatency(2000L)
                .setOverrideDeadline(15000L)
                .build()
            js.schedule(info)
        } catch (_: Exception) {
        }
    }
}

class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Watchdog.ensure(this)
        Watchdog.scheduleTrigger(this) // content triggers are one-shot, so arm it again
        Thread { AutoClean.run(applicationContext) }.start()
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}

// Explains how to stop phone cleaners from force-stopping the app.
fun protectSheet(act: Activity) {
    Sheet.panel(act, "Keep it running") { body ->
        val t = Ui.text(
            act,
            "Cleaner and \"boost\" buttons can force-stop apps. After a force-stop Android blocks everything until the app is opened again, so no app can restart itself. The fix is to tell your phone to leave this app alone:\n\n" +
                "1. Open Recent apps and lock Screenshot Saver (tap its lock icon, or pull its card down).\n\n" +
                "2. Open your phone's cleaner (Phone Master on Tecno, Infinix and itel), go to its settings and add Screenshot Saver to the Whitelist / Protected apps list. The wording differs a little between phones.\n\n" +
                "3. Allow Auto-start for the app, and set its battery use to Unrestricted.\n\n" +
                "4. Add the Screenshot Saver tile: swipe down, tap the pencil, drag the tile up. Opening your quick panel then wakes the app even after a cleaner stopped it.\n\n" +
                "5. You can also share any screenshot to Screenshot Saver from the Share menu. That wakes the app too.",
            13f, Ui.muted()
        )
        t.setPadding(Ui.dp(act, 4), Ui.dp(act, 10), Ui.dp(act, 4), Ui.dp(act, 8))
        body.addView(t)
        body.addView(Ui.button(act, "Open auto-start settings", true, true) { Perms.openAutoStart(act) })
        body.addView(Ui.button(act, "Open battery settings", false, true) { Perms.openBattery(act) })
    }
}
