package com.mremotedroid.app.data.model

/**
 * Connection protocols: RDP runs in the embedded FreeRDP engine, SSH and VNC in the
 * app's own terminal and VNC windows.
 */
enum class Protocol(val displayName: String, val defaultPort: Int) {
    RDP("RDP", 3389),
    VNC("VNC", 5900),
    SSH("SSH", 22);

    companion object {
        fun fromMRemoteName(raw: String?): Protocol = when (raw?.trim()?.uppercase()) {
            "RDP" -> RDP
            "VNC" -> VNC
            "SSH1", "SSH2", "SSH" -> SSH
            else -> RDP
        }
    }
}
