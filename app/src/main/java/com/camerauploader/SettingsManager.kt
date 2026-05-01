package com.camerauploader

import android.content.Context
import android.util.Base64
import android.util.Size
import androidx.core.content.edit

/**
 * Thin wrapper around SharedPreferences for app settings.
 * All components read settings from here — single source of truth.
 */
object SettingsManager {

    private const val PREFS_NAME = "camera_uploader_prefs"

    private const val KEY_UPLOAD_URL    = "upload_url"
    private const val KEY_INTERVAL_SECS = "interval_seconds"
    private const val KEY_AUTH_USERNAME  = "auth_username"
    private const val KEY_RESOLUTION     = "resolution"
    private const val KEY_AUTH_PASSWORD = "auth_password"

    const val DEFAULT_INTERVAL_SECONDS = 300

    // ── Upload URL ────────────────────────────────────────────────────────────

    fun getUploadUrl(context: Context): String =
        prefs(context).getString(KEY_UPLOAD_URL, "") ?: ""

    fun setUploadUrl(context: Context, url: String) =
        prefs(context).edit { putString(KEY_UPLOAD_URL, url.trim()) }

    fun isConfigured(context: Context): Boolean =
        getUploadUrl(context).isNotBlank()

    // ── Capture interval ──────────────────────────────────────────────────────

    fun getIntervalSeconds(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_SECS, DEFAULT_INTERVAL_SECONDS)

    fun setIntervalSeconds(context: Context, seconds: Int) =
        prefs(context).edit { putInt(KEY_INTERVAL_SECS, seconds.coerceAtLeast(1)) }

    // ── Resolution ───────────────────────────────────────────────────────────────

    /**
     * Saved as "WxH" (e.g. "1920x1080"). Empty string means "let CameraX decide"
     * which will use the highest available resolution.
     */
    fun getResolution(context: Context): Size {
        val raw = prefs(context).getString(KEY_RESOLUTION, "") ?: ""
        return if (raw.isBlank()) ResolutionHelper.default() else ResolutionHelper.deserialize(raw)
    }

    fun setResolution(context: Context, size: android.util.Size?) =
        prefs(context).edit {
            putString(KEY_RESOLUTION, if (size == null) "" else ResolutionHelper.serialize(size))
        }

    // ── Basic Auth ────────────────────────────────────────────────────────────

    fun getAuthUsername(context: Context): String =
        prefs(context).getString(KEY_AUTH_USERNAME, "") ?: ""

    fun getAuthPassword(context: Context): String =
        prefs(context).getString(KEY_AUTH_PASSWORD, "") ?: ""

    fun setAuthCredentials(context: Context, username: String, password: String) =
        prefs(context).edit {
            putString(KEY_AUTH_USERNAME, username)
            putString(KEY_AUTH_PASSWORD, password)
        }

    /**
     * Returns a Base64-encoded "Basic ..." header value if a username is set,
     * or null if Basic Auth is not configured.
     */
    fun getBasicAuthHeader(context: Context): String? {
        val user = getAuthUsername(context)
        val pass = getAuthPassword(context)
        if (user.isBlank()) return null
        val encoded = Base64.encodeToString(
            "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        return "Basic $encoded"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
