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

    /** Whether the list checks which servers answer. */
    var showReachability: Boolean
        get() = prefs.getBoolean(KEY_REACHABILITY, true)
        set(value) = prefs.edit().putBoolean(KEY_REACHABILITY, value).apply()

    /** Sessions started so far, for the rating request. */
    val launches: Int get() = prefs.getInt(KEY_LAUNCHES, 0)

    fun countLaunch() {
        val edit = prefs.edit().putInt(KEY_LAUNCHES, launches + 1)
        if (!prefs.contains(KEY_FIRST_LAUNCH)) edit.putLong(KEY_FIRST_LAUNCH, System.currentTimeMillis())
        edit.apply()
    }

    /** Epoch millis of the first session; 0 = none yet. */
    val firstLaunch: Long get() = prefs.getLong(KEY_FIRST_LAUNCH, 0)

    var reviewRequested: Boolean
        get() = prefs.getBoolean(KEY_REVIEW_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_REVIEW_REQUESTED, value).apply()

    private companion object {
        const val KEY_BIOMETRIC_LOCK = "biometric_lock"
        const val KEY_REACHABILITY = "reachability"
        const val KEY_LAUNCHES = "launches"
        const val KEY_FIRST_LAUNCH = "first_launch"
        const val KEY_REVIEW_REQUESTED = "review_requested"
    }
}
