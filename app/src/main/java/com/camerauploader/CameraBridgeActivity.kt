package com.camerauploader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.jvm.java

class CameraBridgeActivity : Activity() {
    val TAG = "CameraBridgeActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "Starting foreground service")

        // Start the camera service now that we are in the foreground
        val serviceIntent = Intent(this, CameraUploaderService::class.java)
        startForegroundService(serviceIntent)

        // Close immediately to keep the screen clear
        finish()
    }
}
