package com.camerauploader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Launcher activity — shows a settings dialog and then disappears.
 *
 * Settings:
 *   - Upload URL (required)
 *   - Capture interval in seconds (default 300)
 *   - Image resolution (spinner populated from the device's supported sizes)
 *   - Optional Basic Auth username / password
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS = 100
    }

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                perms += Manifest.permission.POST_NOTIFICATIONS
            return perms.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmScheduler.cancel(this)
        loadResolutionsAndShowDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        loadResolutionsAndShowDialog()
    }

    // ── Resolution loading ────────────────────────────────────────────────────

    /**
     * Queries supported resolutions on a background thread, then shows the
     * settings dialog on the main thread once the list is ready.
     */
    private fun loadResolutionsAndShowDialog() {
        // Show a brief loading indicator while we query the camera.
        val progress = AlertDialog.Builder(this)
            .setMessage("Loading camera resolutions…")
            .setCancelable(false)
            .create()

        // Only show spinner if camera permission is already granted; on first
        // run we haven't asked yet, so skip the camera query entirely.
        val cameraGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted) {
            progress.show()
            Thread {
                val sizes = ResolutionHelper.getSupportedSizes(applicationContext)
                runOnUiThread {
                    progress.dismiss()
                    showSettingsDialog(sizes)
                }
            }.start()
        } else {
            // No permission yet — show dialog without resolution list; user can
            // change resolution after granting permission on the next open.
            showSettingsDialog(emptyList())
        }
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private fun showSettingsDialog(availableSizes: List<Size>) {
        val s = SettingsManager
        val isFirstRun = !s.isConfigured(this)
        val pad = dpToPx(20)
        val halfPad = dpToPx(8)

        // ── Upload URL ──
        val urlLabel = label("Upload URL *")
        val urlInput = editText(
            value     = s.getUploadUrl(this),
            hint      = "https://example.com/upload",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )

        // ── Interval ──
        val intervalLabel = label("Capture interval (seconds) *")
        val intervalInput = editText(
            value     = s.getIntervalSeconds(this).toString(),
            hint      = "${SettingsManager.DEFAULT_INTERVAL_SECONDS}",
            inputType = InputType.TYPE_CLASS_NUMBER
        )

        // ── Resolution spinner ──
        val resLabel = label("Image resolution")
        val savedSize = s.getResolution(this)

        // Build spinner entries: prepend "Device default (highest)" option.
        data class ResEntry(val size: Size?, val label: String)

        val resEntries = mutableListOf(ResEntry(null, "Device default (highest)"))
        availableSizes.forEach { resEntries += ResEntry(it, ResolutionHelper.format(it)) }

        val resSpinner = Spinner(this).apply {
            val adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                resEntries.map { it.label }
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setAdapter(adapter)

            // Pre-select the saved resolution, falling back to "Device default".
            val savedIndex = if (savedSize == null) 0
                else resEntries.indexOfFirst { it.size == savedSize }.takeIf { it >= 0 } ?: 0
            setSelection(savedIndex)
        }

        val resNote = TextView(this).apply {
            text = if (availableSizes.isEmpty())
                "Grant camera permission and re-open settings to see supported resolutions."
            else
                "${availableSizes.size} resolutions available."
            textSize = 11f
            setTextColor(0xFF888888.toInt())
            setPadding(0, dpToPx(2), 0, 0)
        }

        // ── Basic Auth ──
        val authHeader = TextView(this).apply {
            text = "Basic Auth (optional)"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dpToPx(12), 0, 0)
        }
        val authSubtitle = TextView(this).apply {
            text = "Leave blank to disable authentication."
            textSize = 12f
            setPadding(0, 0, 0, halfPad)
            setTextColor(0xFF888888.toInt())
        }

        val userLabel = label("Username")
        val userInput = editText(
            value     = s.getAuthUsername(this),
            hint      = "username",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )

        val passLabel = label("Password")
        val passInput = editText(
            value     = s.getAuthPassword(this),
            hint      = "password",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )

        val showPassCheck = CheckBox(this).apply {
            text = "Show password"
            setOnCheckedChangeListener { _, checked ->
                passInput.inputType = if (checked)
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                else
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                passInput.setSelection(passInput.text.length)
            }
        }

        // ── Assemble layout ──
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, halfPad, pad, halfPad)
            addView(urlLabel)
            addView(urlInput)
            addView(intervalLabel)
            addView(intervalInput)
            addView(resLabel)
            addView(resSpinner)
            addView(resNote)
            addView(authHeader)
            addView(authSubtitle)
            addView(userLabel)
            addView(userInput)
            addView(passLabel)
            addView(passInput)
            addView(showPassCheck)
        }

        AlertDialog.Builder(this)
            .setTitle(if (isFirstRun) "Configure uploader" else "Settings")
            .setView(ScrollView(this).apply { addView(container) })
            .setCancelable(!isFirstRun)
            .setPositiveButton("Save & Start") { _, _ ->
                val url = urlInput.text.toString().trim()
                val intervalSecs = intervalInput.text.toString().trim().toIntOrNull() ?: 0

                if (url.isBlank() || !url.startsWith("http")) {
                    toast("Please enter a valid URL starting with https://")
                    showSettingsDialog(availableSizes); return@setPositiveButton
                }
                if (intervalSecs < 1) {
                    toast("Interval must be at least 1 second")
                    showSettingsDialog(availableSizes); return@setPositiveButton
                }

                s.setUploadUrl(this, url)
                s.setIntervalSeconds(this, intervalSecs)
                s.setResolution(this, resEntries[resSpinner.selectedItemPosition].size)
                s.setAuthCredentials(
                    this,
                    userInput.text.toString().trim(),
                    passInput.text.toString()
                )

                proceedAfterSettingsSaved()
            }
            .apply {
                if (!isFirstRun)
                    setNegativeButton("Cancel") { _, _ -> finish() }
            }
            .setOnCancelListener { finish() }
            .show()
            .also { dialog ->
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
    }

    private fun proceedAfterSettingsSaved() {
        if (allPermissionsGranted()) {
            restartUploaderService()
            toast("Uploader running ✓")
            finish()
        } else {
            ActivityCompat.requestPermissions(this, requiredPermissions, REQUEST_PERMISSIONS)
        }
    }

    // ── Permission result ─────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (allPermissionsGranted()) {
                restartUploaderService()
                toast("Uploader running ✓")
            } else {
                toast("Camera permission is required for the uploader to work.")
            }
            finish()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun restartUploaderService() {
        // Cancel any existing alarm, then arm a fresh one.
        // Also kick off an immediate first capture by starting the service directly.
        AlarmScheduler.scheduleNext(this)
        val intent = Intent(this, CameraUploaderService::class.java).apply {
            action = CameraUploaderWorker.ACTION_CAPTURE
        }
        startForegroundService(intent)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dpToPx(10), 0, dpToPx(2))
    }

    private fun editText(value: String, hint: String, inputType: Int) =
        EditText(this).apply {
            this.inputType = inputType
            this.hint = hint
            setText(value)
            setSelection(text.length)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()
}
