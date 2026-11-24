package com.mremotedroid.app.launch

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory
import com.mremotedroid.app.data.settings.AppSettings

/**
 * Asks once for a Google Play rating, through Play's own in-app dialog, after the app has
 * proved useful: several sessions over a few days. Play decides whether the dialog is
 * actually shown; outside Play installs nothing happens.
 */
object ReviewPrompt {
    private const val MIN_LAUNCHES = 5
    private const val MIN_DAYS_MS = 3L * 24 * 60 * 60 * 1000

    fun maybeAsk(activity: Activity, settings: AppSettings) {
        if (settings.reviewRequested || settings.launches < MIN_LAUNCHES) return
        val first = settings.firstLaunch
        if (first == 0L || System.currentTimeMillis() - first < MIN_DAYS_MS) return
        settings.reviewRequested = true
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (request.isSuccessful && !activity.isFinishing && !activity.isDestroyed) {
                    manager.launchReviewFlow(activity, request.result)
                }
            }
        }.onFailure { Log.w("ReviewPrompt", "review flow unavailable", it) }
    }
}
