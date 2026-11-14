package com.mremotedroid.app.launch

import android.content.Context
import java.io.File

/**
 * The folder a session can share with the server. An app-specific directory is used
 * because the RDP drive channel needs real paths, and reaching shared storage that way
 * would take the all-files permission.
 */
object SharedFiles {
    const val DRIVE_NAME = "mRemoteDroid"
    const val ROOT_ID = "shared"

    fun authority(context: Context): String = "${context.packageName}.shared"

    fun directory(context: Context): File? =
        context.getExternalFilesDir("Shared")?.apply { mkdirs() }
}
