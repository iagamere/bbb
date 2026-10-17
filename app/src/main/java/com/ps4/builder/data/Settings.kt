package com.ps4.builder.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * All settings live in EncryptedSharedPreferences (AES256). The token is never logged or shown in messages.
 * The constructor throws if the Android keystore is unusable; the caller shows that error (no plaintext fallback).
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_settings", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v.trim()).apply() }

    /** "owner/repo" */
    var repo: String
        get() = prefs.getString("repo", "") ?: ""
        set(v) { prefs.edit().putString("repo", v.trim()).apply() }

    var ip: String
        get() = prefs.getString("ip", "") ?: ""
        set(v) { prefs.edit().putString("ip", v.trim()).apply() }

    /** "ar" (default) or "en" */
    var lang: String
        get() = prefs.getString("lang", "ar") ?: "ar"
        set(v) { prefs.edit().putString("lang", v).apply() }

    /** Run being tracked, so tracking resumes if Android killed the app meanwhile. 0 = none. */
    var pendingRunId: Long
        get() = prefs.getLong("pendingRunId", 0L)
        set(v) { prefs.edit().putLong("pendingRunId", v).apply() }

    var pendingZip: String
        get() = prefs.getString("pendingZip", "") ?: ""
        set(v) { prefs.edit().putString("pendingZip", v).apply() }
}
