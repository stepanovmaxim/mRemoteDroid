package com.mremotedroid.app.launch

import android.net.Uri
import com.freerdp.freerdpcore.application.GlobalApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which connections currently have an open session window. Each session is tagged
 * with a non-secret URI derived from the connection's id ([tagFor]); FreeRDP's
 * [GlobalApp] reports whenever a session is opened or closed.
 */
object ActiveSessions {
    private const val SCHEME = "mremotedroid"
    private const val HOST = "session"

    private val _nodeIds = MutableStateFlow<Set<String>>(emptySet())
    val nodeIds: StateFlow<Set<String>> = _nodeIds.asStateFlow()

    fun init() {
        GlobalApp.addSessionListener { refresh() }
        refresh()
    }

    fun tagFor(nodeId: String): String =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(nodeId).build().toString()

    fun isOpen(nodeId: String): Boolean = GlobalApp.findSessionByTag(tagFor(nodeId)) != null

    fun count(): Int = GlobalApp.getSessions().size

    private fun refresh() {
        _nodeIds.value = GlobalApp.getSessions().mapNotNull { s ->
            val uri = s.tag?.let(Uri::parse) ?: return@mapNotNull null
            if (uri.scheme == SCHEME && uri.authority == HOST) uri.lastPathSegment else null
        }.toSet()
    }
}
