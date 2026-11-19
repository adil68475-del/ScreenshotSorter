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
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
