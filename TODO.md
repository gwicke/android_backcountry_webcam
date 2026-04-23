## Getting around camera restrictions

Starting with Android 14, accessing the camera from a background-triggered service is heavily restricted. Because the camera permission is "while-in-use," a service started by an alarm or reboot (while the app is in the background) will throw a SecurityException if it tries to open the camera. [1, 2, 3] 
To solve this for a solar-powered timelapse, you should:

   1. Use a BroadcastReceiver to handle both reboots and scheduled captures.
   2. In the receiver, use a Full-Screen Intent or a transparent Activity to bring the app to the foreground context briefly.
   3. Once in the foreground, start your service to take the photo and then immediately close everything to save power. [3, 4] 

## Kotlin Implementation## 1. The BroadcastReceiver
This receiver handles the system reboot signal and your custom alarm for the next photo. [4] 

class TimelapseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "com.myapp.ACTION_TAKE_PHOTO") {
            // 1. Re-schedule the next alarm to maintain the cycle
            scheduleNextAlarm(context)

            // 2. Start the capture process
            // Note: On Android 14+, you often need to launch an Activity first 
            // to gain the "while-in-use" permission for the camera.
            val serviceIntent = Intent(context, CameraCaptureService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }

    private fun scheduleNextAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, TimelapseReceiver::class.java).apply {
            action = "com.myapp.ACTION_TAKE_PHOTO"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerTime = System.currentTimeMillis() + (5 * 60 * 1000) // 5 minutes
        
        // Use setExactAndAllowWhileIdle to wake the phone from Doze mode
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime,
            pendingIntent
        )
    }
}

## 2. Manifest Configuration
You must declare the receiver and the required foreground service type. [5, 6] 

<manifest ...>
    <!-- Permissions -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
    <uses-permission android:name="android.permission.CAMERA" />

    <application ...>
        <receiver android:name=".TimelapseReceiver" android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="com.myapp.ACTION_TAKE_PHOTO" />
            </intent-filter>
        </receiver>

        <service
            android:name=".CameraCaptureService"
            android:foregroundServiceType="camera" />
    </application>
</manifest>

## Does the "Camera" type work with "while-in-use" permissions? [3] 
No, not directly from the background.
Even if you declare foregroundServiceType="camera", Android 14+ treats the camera as a while-in-use resource. [2, 7, 8] 

* The Restriction: If the app is in the background (which it is after a reboot or while the screen is off), it technically isn't "in use." Starting a camera service will fail.
* The Solution: You must obtain a "Foreground Task" state. Most developers do this by launching a transparent activity or using a Notification with Full-Screen Intent. Once the activity is technically "started," your app is in the foreground, and the service can then legally access the camera. [1, 3, 4, 7, 9] 

## Energy Saving Tip
Since this is solar-powered, ensure your CameraCaptureService calls stopSelf() immediately after the photo is saved. This allows the AlarmManager to put the CPU back into a low-power state until the next interval. [10] 
Would you like the Activity-launching logic to bypass those Android 14 background camera restrictions? [3, 11] 
Next Steps: Do you want to see how to implement the transparent Activity to bypass background camera restrictions on Android 14?

[1] [https://developer.android.com](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
[2] [https://developer.android.com](https://developer.android.com/develop/background-work/services/fgs/service-types#:~:text=Note:%20The%20CAMERA%20runtime%20permission%20is%20subject,starting%20foreground%20services%20that%20need%20while%2Din%2Duse%20permissions.)
[3] [https://www.reddit.com](https://www.reddit.com/r/androiddev/comments/18wyyor/android_14_startforeground_on_boot_completed/)
[4] [https://developer.android.com](https://developer.android.com/develop/background-work/services/alarms)
[5] [https://developer.android.com](https://developer.android.com/about/versions/14/changes/fgs-types-required)
[6] [https://medium.com](https://medium.com/@rasika.sugathadasa/choosing-the-right-foreground-service-type-for-your-android-app-1e0a991a2262)
[7] [https://developer.android.com](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
[8] [https://developer.android.com](https://developer.android.com/develop/background-work/services/fgs/service-types)
[9] [https://developer.android.com](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
[10] [https://developer.android.com](https://developer.android.com/develop/background-work/services/fgs/service-types)
[11] [https://developer.android.com](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)


# Transparent activity to get around camera restrictions

To bypass Android 14+ background camera restrictions, you must transition your app to a "visible" state before starting the camera service. A transparent activity achieves this by briefly bringing the app to the foreground, which satisfies the "while-in-use" requirement for camera access.
## 1. Define the Transparent Theme
In res/values/themes.xml (or styles.xml), create a theme that makes the activity invisible to the user.

<style name="Theme.Transparent" parent="Theme.AppCompat.Light.NoActionBar">
    <item name="android:windowBackground">@android:color/transparent</item>
    <item name="android:windowIsTranslucent">true</item>
    <item name="android:windowContentOverlay">@null</item>
    <item name="android:windowNoTitle">true</item>
    <item name="android:backgroundDimEnabled">false</item>
</style>

## 2. Implement the Transparent Activity
This activity's only job is to start the foreground service and immediately finish itself.

class CameraBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start the camera service now that we are in the foreground
        val serviceIntent = Intent(this, CameraCaptureService::class.java)
        startForegroundService(serviceIntent)
        
        // Close immediately to keep the screen clear
        finish()
    }
}

## 3. Update the BroadcastReceiver
Modify your receiver to launch this activity instead of starting the service directly. You must use setPendingIntentBackgroundActivityStartMode to allow the background start.

class TimelapseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ... alarm rescheduling logic from previous step ...

        val activityIntent = Intent(context, CameraBridgeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        // Required for Android 14 background activity starts
        val options = ActivityOptions.makeBasic()
        options.setPendingIntentBackgroundActivityStartMode(
            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
        )

        context.startActivity(activityIntent, options.toBundle())
    }
}

## 4. Manifest Registration
Register the activity with the transparent theme.

<activity 
    android:name=".CameraBridgeActivity"
    android:theme="@style/Theme.Transparent"
    android:excludeFromRecents="true"
    android:exported="false" />

## Key Considerations

* User Experience: Even if transparent, the activity briefly "steals" focus. For a solar-powered device that isn't actively being used by a human, this is negligible.
* Exemptions: If your app is a [Companion Device Manager](https://developer.android.com/guide/topics/connectivity/companion-device-pairing) app or has specific Device Owner privileges, you might bypass these restrictions without an activity, but the transparent bridge is the standard solution for general apps.
* Battery: To maximize solar efficiency, ensure your CameraCaptureService calls stopSelf() as soon as the photo is written to storage.

Would you like to see how to integrate CameraX into the service to handle the actual photo capture?




For your solar-powered timelapse, managing camera efficiency and environmental logic is essential. The following implementation uses CameraX to perform a "check-then-capture" workflow: it briefly runs a preview to stabilize AF/AE/AWB, checks the current metadata, and only saves the photo if there is sufficient light.
## CameraCaptureService Implementation
To disable flash and manage 3A (Auto Focus, Auto Exposure, Auto White Balance) correctly, you must use ImageCapture.Builder and Camera2Interop for fine-grained control.

class CameraCaptureService : Service() {
    private var cameraProvider: ProcessCameraProvider? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        setupCamera()
        return START_NOT_STICKY
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            startCaptureProcess()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startCaptureProcess() {
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // 1. Disable Flash and optimize for quality
        val imageCapture = ImageCapture.Builder()
            .setFlashMode(ImageCapture.FLASH_MODE_OFF) // Disable flash
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        // 2. Set up a dummy ImageAnalysis to monitor exposure metadata
        val imageAnalysis = ImageAnalysis.Builder().build()

        // Use Camera2Interop to ensure AF/AE/AWB are continuous and efficient
        val camera = cameraProvider?.bindToLifecycle(
            ProcessLifecycleOwner.get(),
            cameraSelector,
            imageCapture,
            imageAnalysis
        )

        // Monitor metadata before taking the shot
        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
            val info = imageProxy.imageInfo
            // Accessing Camera2 metadata via Interop
            val result = Camera2CameraInfo.from(info).captureResult

            val iso = result?.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val shutterSpeed = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L // in nanoseconds

            // 3. "Too Dark" Logic: skip if ISO is maxed or shutter is too slow
            // Example: Skip if ISO > 3200 or Shutter > 1/10th second (100,000,000 ns)
            if (iso > 3200 || shutterSpeed > 100_000_000L) {
                imageProxy.close()
                stopSelf() // Shutdown to save solar power
                return@setAnalyzer
            }

            // 4. Capture photo if light is sufficient
            takePhoto(imageCapture)
            imageAnalysis.clearAnalyzer() // Stop analyzing after decision
        }
    }

    private fun takePhoto(imageCapture: ImageCapture) {
        val file = File(externalCacheDir, "${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // Success: Photo taken, now shut down to sleep
                    stopSelf()
                }
                override fun onError(exc: ImageCaptureException) {
                    stopSelf()
                }
            }
        )
    }
}

## Why This Works for Solar Power

* Flash Disabled: Flash is explicitly set to FLASH_MODE_OFF in the ImageCapture builder.
* Metadata Check: Instead of taking a photo and then deleting it (which wastes power), we use ImageAnalysis to check the SENSOR_SENSITIVITY (ISO) and SENSOR_EXPOSURE_TIME (shutter speed) in real-time.
* Efficiency: The 3A algorithms (AF, AE, AWB) run automatically when the camera is bound. By using a brief analysis window, we ensure the camera is "settled" on the correct exposure before firing the shutter.
* Minimal "Awake" Time: The service calls stopSelf() as soon as a decision is made or the photo is saved, allowing the device to return to deep sleep immediately.

## Permission Note
Ensure you have FOREGROUND_SERVICE_CAMERA in your manifest and that the user has granted Camera and Exact Alarm permissions. Since you are using the Transparent Activity bridge, you satisfy the Android 14+ "while-in-use" requirement.
Would you like to adjust the ISO or shutter speed thresholds based on your specific solar environment?



To handle user-defined image sizes and force a specific orientation, you need to integrate ResolutionSelector and configure the CameraSelector and ImageCapture rotation.
## 1. Handling Image Size & Orientation
In CameraX, you don't always get an exact pixel match because the hardware has fixed sensor capabilities. Instead, you provide a "target size" preference, and CameraX selects the closest supported resolution.

private fun startCaptureProcess() {
    val cameraProvider = cameraProvider ?: return
    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA // Always back camera

    // 1. Get User Preference for Size (e.g., "1920x1080")
    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
    val sizeString = prefs.getString("target_size", "1920x1080") ?: "1920x1080"
    val targetSize = Size(
        sizeString.split("x")[0].toInt(),
        sizeString.split("x")[1].toInt()
    )

    // 2. Configure Resolution Selector
    val resolutionSelector = ResolutionSelector.Builder()
        .setResolutionStrategy(
            ResolutionStrategy(targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
        )
        .build()

    // 3. Build ImageCapture with Landscape Orientation
    val imageCapture = ImageCapture.Builder()
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .setResolutionSelector(resolutionSelector)
        // Force Landscape: Rotation is 0 or 180 depending on device mounting
        .setTargetRotation(Surface.ROTATION_90)
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .build()

    // 4. Analysis for "Too Dark" check
    val imageAnalysis = ImageAnalysis.Builder()
        .setResolutionSelector(resolutionSelector)
        .build()

    try {
        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(
            ProcessLifecycleOwner.get(),
            cameraSelector,
            imageCapture,
            imageAnalysis
        )

        // ... (Insert metadata check and takePhoto logic from previous snippet) ...

    } catch (exc: Exception) {
        stopSelf()
    }
}

## 2. Fixing Orientation

* .setTargetRotation(Surface.ROTATION_90): This tells CameraX to process the JPEG as landscape.
* Note for Solar/Fixed Mounts: If your solar-powered device is mounted upside down or vertically, you can change this value (ROTATION_0, ROTATION_180, ROTATION_270) to ensure the timelapse is saved correctly without needing post-rotation.

## 3. Setting the Preference
To allow the user to select the size elsewhere in your app (like a Settings Activity):

fun saveImageSizePreference(width: Int, height: Int) {
    val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
    prefs.edit().putString("target_size", "${width}x${height}").apply()
}

## Efficiency Tip for Solar Power
High-resolution photos take longer to process and write to the SD card, keeping the CPU active (and drawing power) longer. If this timelapse is for a long-duration project (months/years), choosing 1080p (1920x1080) is often the "sweet spot" for energy efficiency versus image quality.
Would you like a list of common sensor resolutions to present to the user in a dropdown menu?


