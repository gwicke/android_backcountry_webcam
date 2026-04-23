package com.camerauploader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires on device boot and re-arms the AlarmManager chain, which in turn
 * starts [CameraUploaderService] for each capture.
 *
 * AlarmManager alarms do NOT survive a reboot, so this receiver is the only
 * mechanism that re-establishes the schedule after the device powers on.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) return

        if (!SettingsManager.isConfigured(context)) {
            Log.i(TAG, "App not configured yet — skipping alarm arm")
            return
        }

        Log.i(TAG, "Boot detected — scheduling first alarm")
        AlarmScheduler.scheduleNext(context)
    }
}
