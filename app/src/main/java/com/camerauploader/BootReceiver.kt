package com.camerauploader

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Fires on device boot and re-arms the AlarmManager chain, which in turn
 * starts [CameraUploaderWorker] for each capture.
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

        Log.i(TAG, "Boot detected — starting capture service")
        val activityIntent = Intent(context, CameraBridgeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Required for Android 14 background activity starts
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            options.setPendingIntentBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            )
        }
        context.startActivity(activityIntent, options.toBundle())

        // Schedule the next iteration
        AlarmScheduler.scheduleNext(context)
    }
}
