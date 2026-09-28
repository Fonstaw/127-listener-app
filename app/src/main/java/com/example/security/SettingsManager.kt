package com.example.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

object SettingsManager {
    private const val TAG = "SettingsManager"
    private const val ENCRYPTED_PREFS_FILE = "secure_settings_prefs"
    private const val FALLBACK_PREFS_FILE = "telebirr_settings_fallback_prefs"
    
    private const val KEY_API_URL = "api_url"
    private const val KEY_API_TOKEN = "api_token"
    private const val KEY_DELETE_PASSCODE = "delete_passcode"
    private const val KEY_PASSCODE_SET = "is_passcode_customized"
    private const val KEY_DEVICE_BINDING_ID = "device_binding_id"
    private const val KEY_LAST_SCANNED_SMS_DATE = "last_scanned_sms_date"
    private const val KEY_LAST_SCANNED_SMS_ID = "last_scanned_sms_id"
    private const val KEY_LAST_SCAN_TIMESTAMP = "last_scan_timestamp"

    const val DEFAULT_URL = "https://your-bot-server.com/"
    const val DEFAULT_TOKEN = ""
    const val DEFAULT_DELETE_PASSCODE = "1234"

    @Volatile
    private var sharedPrefsInstance: SharedPreferences? = null

    private fun getSharedPrefs(context: Context): SharedPreferences {
        sharedPrefsInstance?.let { return it }

        synchronized(this) {
            sharedPrefsInstance?.let { return it }

            val appContext = context.applicationContext
            var prefs: SharedPreferences? = null

            // 1. Try to initialize EncryptedSharedPreferences
            try {
                val masterKey = MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                prefs = EncryptedSharedPreferences.create(
                    appContext,
                    ENCRYPTED_PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (t: Throwable) {
                Log.w(TAG, "EncryptedSharedPreferences initialization failed, wiping stale file if present: ${t.message}")
                try {
                    // Try to purge the corrupted shared prefs xml file if it caused crypto failure
                    val prefsFile = File(appContext.filesDir.parent, "shared_prefs/$ENCRYPTED_PREFS_FILE.xml")
                    if (prefsFile.exists()) {
                        prefsFile.delete()
                    }
                } catch (ignored: Throwable) {}
            }

            // 2. If EncryptedSharedPreferences failed, use standard fallback SharedPreferences
            if (prefs == null) {
                try {
                    prefs = appContext.getSharedPreferences(FALLBACK_PREFS_FILE, Context.MODE_PRIVATE)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to obtain fallback SharedPreferences", t)
                }
            }

            // 3. Final safety fallback to avoid any null pointers
            val finalPrefs = prefs ?: appContext.getSharedPreferences("telebirr_emergency_prefs", Context.MODE_PRIVATE)
            sharedPrefsInstance = finalPrefs
            return finalPrefs
        }
    }

    fun getApiUrl(context: Context): String {
        return try {
            val url = getSharedPrefs(context).getString(KEY_API_URL, DEFAULT_URL)
            if (url.isNullOrBlank()) DEFAULT_URL else url
        } catch (t: Throwable) {
            Log.e(TAG, "Error reading API URL", t)
            DEFAULT_URL
        }
    }

    fun saveApiUrl(context: Context, url: String) {
        try {
            getSharedPrefs(context).edit().putString(KEY_API_URL, url).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save API URL", t)
        }
    }

    fun getApiToken(context: Context): String {
        return try {
            val token = getSharedPrefs(context).getString(KEY_API_TOKEN, DEFAULT_TOKEN)
            if (token.isNullOrBlank()) DEFAULT_TOKEN else token
        } catch (t: Throwable) {
            Log.e(TAG, "Error reading API Token", t)
            DEFAULT_TOKEN
        }
    }

    fun saveApiToken(context: Context, token: String) {
        try {
            getSharedPrefs(context).edit().putString(KEY_API_TOKEN, token).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save API Token", t)
        }
    }

    /**
     * Returns true if the user has explicitly established a custom passcode.
     */
    fun getOrCreateDeviceBindingId(context: Context): String {
        return try {
            val prefs = getSharedPrefs(context)
            val existing = prefs.getString(KEY_DEVICE_BINDING_ID, null)
            if (!existing.isNullOrBlank()) {
                existing
            } else {
                val newId = java.util.UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_BINDING_ID, newId).apply()
                newId
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error obtaining device binding ID", t)
            java.util.UUID.randomUUID().toString()
        }
    }

    fun hasCustomPasscode(context: Context): Boolean {
        return try {
            getSharedPrefs(context).getBoolean(KEY_PASSCODE_SET, false)
        } catch (t: Throwable) {
            false
        }
    }

    fun getDeletePasscode(context: Context): String {
        return try {
            val stored = getSharedPrefs(context).getString(KEY_DELETE_PASSCODE, null)
            if (stored.isNullOrBlank()) DEFAULT_DELETE_PASSCODE else stored
        } catch (t: Throwable) {
            DEFAULT_DELETE_PASSCODE
        }
    }

    fun saveDeletePasscode(context: Context, passcode: String) {
        val clean = passcode.trim()
        if (clean.length == 4 && clean.all { it.isDigit() }) {
            try {
                getSharedPrefs(context).edit()
                    .putString(KEY_DELETE_PASSCODE, clean)
                    .putBoolean(KEY_PASSCODE_SET, true)
                    .apply()
                Log.d(TAG, "Passcode updated successfully")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to save passcode", t)
            }
        }
    }

    fun verifyDeletePasscode(context: Context, input: String): Boolean {
        val cleanInput = input.trim()
        if (cleanInput.length != 4) return false
        val actual = getDeletePasscode(context)
        return cleanInput == actual || (!hasCustomPasscode(context) && cleanInput == DEFAULT_DELETE_PASSCODE)
    }

    fun resetPasscodeToDefault(context: Context) {
        try {
            getSharedPrefs(context).edit()
                .putString(KEY_DELETE_PASSCODE, DEFAULT_DELETE_PASSCODE)
                .putBoolean(KEY_PASSCODE_SET, false)
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to reset passcode", t)
        }
    }

    /**
     * Retrieves the persisted SMS inbox scan checkpoint (lastScannedDate to lastScannedSmsId).
     */
    fun getScanCheckpoint(context: Context): Pair<Long, Long> {
        return try {
            val prefs = getSharedPrefs(context)
            val date = prefs.getLong(KEY_LAST_SCANNED_SMS_DATE, 0L)
            val id = prefs.getLong(KEY_LAST_SCANNED_SMS_ID, 0L)
            Pair(date, id)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to get scan checkpoint", t)
            Pair(0L, 0L)
        }
    }

    /**
     * Persists the SMS inbox scan checkpoint atomically.
     */
    fun saveScanCheckpoint(context: Context, lastScannedDate: Long, lastScannedSmsId: Long) {
        try {
            getSharedPrefs(context).edit()
                .putLong(KEY_LAST_SCANNED_SMS_DATE, lastScannedDate)
                .putLong(KEY_LAST_SCANNED_SMS_ID, lastScannedSmsId)
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save scan checkpoint", t)
        }
    }

    /**
     * Resets the scan checkpoint to 0, forcing the next scan to inspect all messages (recovery/full scan).
     */
    fun resetScanCheckpoint(context: Context) {
        try {
            getSharedPrefs(context).edit()
                .putLong(KEY_LAST_SCANNED_SMS_DATE, 0L)
                .putLong(KEY_LAST_SCANNED_SMS_ID, 0L)
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to reset scan checkpoint", t)
        }
    }

    fun getLastScanTimestamp(context: Context): Long {
        return try {
            getSharedPrefs(context).getLong(KEY_LAST_SCAN_TIMESTAMP, 0L)
        } catch (t: Throwable) {
            0L
        }
    }

    fun saveLastScanTimestamp(context: Context, timestamp: Long) {
        try {
            getSharedPrefs(context).edit()
                .putLong(KEY_LAST_SCAN_TIMESTAMP, timestamp)
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to save last scan timestamp", t)
        }
    }
}
