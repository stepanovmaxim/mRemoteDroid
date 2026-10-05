package com.mremotedroid.app

import android.os.Build
import android.util.Log
import com.freerdp.freerdpcore.application.GlobalApp
import com.mremotedroid.app.data.db.AppDatabase
import com.mremotedroid.app.data.repo.ConnectionRepository
import com.mremotedroid.app.launch.ActiveSessions
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

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
        installCrashReporter()
        try {
            super.onCreate()
            embeddedRdpAvailable = true
            ActiveSessions.init()
        } catch (t: Throwable) {
            // Application.onCreate already ran inside GlobalApp before the native load.
            Log.e("MRemoteApp", "FreeRDP native init failed; embedded RDP disabled", t)
        }
        val db = AppDatabase.get(this)
        repository = ConnectionRepository(db.nodeDao())
    }

    /**
     * Saves the stack trace of an uncaught exception so the next start can offer to
     * share it (see MainActivity). Native crashes inside FreeRDP are not caught here.
     */
    private fun installCrashReporter() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                val version = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "?"
                val report = buildString {
                    appendLine("mRemoteDroid $version")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(trace)
                }
                File(filesDir, CRASH_FILE).writeText(redact(report))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"

        /** Never leak passwords that might appear in a freerdp:// URI inside a message. */
        fun redact(text: String): String =
            text.replace(Regex("([?&](?:p|gp)=)[^&\\s]*"), "$1***")
    }
}
