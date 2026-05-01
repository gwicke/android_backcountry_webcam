package com.camerauploader

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat.startForegroundService

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Directly call the service intent
        val serviceIntent = Intent(context, CameraUploaderService::class.java)
        startForegroundService(context, serviceIntent)

        // Schedule the next iteration
        AlarmScheduler.scheduleNext(context)
    }
}
