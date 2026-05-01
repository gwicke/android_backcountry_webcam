package com.camerauploader

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Manages the repeating AlarmManager alarm that triggers captures.
 *
 * We use [AlarmManager.setExactAndAllowWhileIdle] on a rolling basis
 * (each alarm schedules the next one) rather than [AlarmManager.setRepeating],
 * because setRepeating is inexact on API 19+ and does not fire during Doze.
 *
 * On Android 12+ (API 31), exact alarms require the SCHEDULE_EXACT_ALARM or
 * USE_EXACT_ALARM permission.  We request SCHEDULE_EXACT_ALARM and fall back
 * to inexact [AlarmManager.setAndAllowWhileIdle] if the user has not granted it.
 */
object AlarmScheduler {

    private const val TAG = "AlarmScheduler"
    private const val REQUEST_CODE = 0

    /**
     * Schedule (or reschedule) the next single-shot alarm.
     * Call this from [CameraUploaderWorker] at the end of each capture cycle,
     * and from [BootReceiver] / [MainActivity] to start the chain.
     */
    fun scheduleNext(context: Context) {
        val intervalMs = SettingsManager.getIntervalSeconds(context).toLong()
            .coerceAtLeast(1L) * 1_000L
        val triggerAt = (SystemClock.elapsedRealtime() / intervalMs + 1) * intervalMs

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = buildPendingIntent(context)

        when {
            // API 31+: use exact alarm if permission is granted, else inexact
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending
                    )
                    Log.d(TAG, "Exact alarm in ${intervalMs / 1000}s")
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending
                    )
                    Log.w(TAG, "Exact alarm permission not granted — using inexact alarm")
                }
            }
            // API 23–30: setExactAndAllowWhileIdle works without extra permission
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending
                )
                Log.d(TAG, "Exact alarm (Doze-safe) in ${intervalMs / 1000}s")
            }
            // API < 23
            else -> {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pending)
                Log.d(TAG, "Exact alarm in ${intervalMs / 1000}s")
            }
        }
    }

    /** Cancel any pending alarm (call when the user disables the service). */
    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(buildPendingIntent(context))
        Log.d(TAG, "Alarm cancelled")
    }

    private fun buildPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }
}
