package com.mremotedroid.app.data.settings

import android.content.Context

/**
 * Lightweight synchronous settings backed by SharedPreferences. Only a couple of
 * flags live here, and the launch flow needs them synchronously, so a full DataStore
 * would be overkill.
 */
class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** When true, stored passwords require a biometric/device-credential unlock before use. */
    var biometricLock: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_LOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_LOCK, value).apply()

    private companion object {
        const val KEY_BIOMETRIC_LOCK = "biometric_lock"
    }
}
