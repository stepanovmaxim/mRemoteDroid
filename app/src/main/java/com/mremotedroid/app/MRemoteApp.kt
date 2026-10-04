package com.mremotedroid.app

import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import com.mremotedroid.app.data.db.AppDatabase
import com.mremotedroid.app.data.repo.ConnectionRepository

/**
 * Extends FreeRDP's [GlobalApp] so the embedded-session machinery is set up:
 * GlobalApp.onCreate() wires LibFreeRDP's native event listener and the session
 * map that [com.freerdp.freerdpcore.presentation.SessionActivity] relies on.
 *
 * That init also triggers loading the native FreeRDP libraries. We guard it so a
 * load failure (e.g. an unsupported ABI) never prevents the app from starting —
 * only the embedded RDP mode is unavailable in that case; external clients still
 * work.
 */
class MRemoteApp : GlobalApp() {
    lateinit var repository: ConnectionRepository
        private set

    /** True once the FreeRDP native stack initialised successfully. */
    var embeddedRdpAvailable: Boolean = false
        private set

    override fun onCreate() {
        try {
            super.onCreate()
            embeddedRdpAvailable = true
        } catch (t: Throwable) {
            // Application.onCreate already ran inside GlobalApp before the native load.
            Log.e("MRemoteApp", "FreeRDP native init failed; embedded RDP disabled", t)
        }
        val db = AppDatabase.get(this)
        repository = ConnectionRepository(db.nodeDao())
    }
}
