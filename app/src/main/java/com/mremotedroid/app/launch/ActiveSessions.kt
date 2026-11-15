package com.mremotedroid.app.launch

import android.net.Uri
import com.freerdp.freerdpcore.application.GlobalApp
import com.mremotedroid.app.remote.RemoteSessions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which connections currently have an open session window. Each session is tagged
 * with a non-secret URI derived from the connection's id ([tagFor]); FreeRDP's
 * [GlobalApp] reports whenever an RDP session is opened or closed, [RemoteSessions]
 * does the same for SSH and VNC.
 */
object ActiveSessions {
    private const val SCHEME = "mremotedroid"
    private const val HOST = "session"

    private val _nodeIds = MutableStateFlow<Set<String>>(emptySet())
    val nodeIds: StateFlow<Set<String>> = _nodeIds.asStateFlow()

    fun init() {
        GlobalApp.addSessionListener { refresh() }
        RemoteSessions.addListener { refresh() }
        refresh()
    }

    fun tagFor(nodeId: String): String =
        Uri.Builder().scheme(SCHEME).authority(HOST).appendPath(nodeId).build().toString()

    fun isOpen(nodeId: String): Boolean =
        GlobalApp.findSessionByTag(tagFor(nodeId)) != null || RemoteSessions.find(tagFor(nodeId)) != null

    fun count(): Int = GlobalApp.getSessions().size + RemoteSessions.all().size

    private fun refresh() {
        val tags = GlobalApp.getSessions().mapNotNull { it.tag } + RemoteSessions.all().map { it.tag }
        _nodeIds.value = tags.mapNotNull { tag ->
            val uri = Uri.parse(tag)
            if (uri.scheme == SCHEME && uri.authority == HOST) uri.lastPathSegment else null
        }.toSet()
    }
}
