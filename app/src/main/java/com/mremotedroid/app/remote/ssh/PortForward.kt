package com.mremotedroid.app.remote.ssh

/**
 * A port forward of an SSH connection, written like OpenSSH's options, one per line:
 * `L 8080:intranet:80`, `R 9000:localhost:22`, `D 1080`.
 */
sealed interface PortForward {
    /** 127.0.0.1:[localPort] on the device leads to [host]:[port] as seen from the server. */
    data class Local(val localPort: Int, val host: String, val port: Int) : PortForward

    /** Port [remotePort] on the server leads to [host]:[port] as seen from the device. */
    data class Remote(val remotePort: Int, val host: String, val port: Int) : PortForward

    /** A SOCKS proxy on 127.0.0.1:[localPort] that connects through the server. */
    data class Dynamic(val localPort: Int) : PortForward

    data class Parsed(val forwards: List<PortForward>, val invalid: List<String>)

    companion object {
        private val HOST_PORT = Regex("""^(\d{1,5}):(\[[^\]]+]|[^:\s]+):(\d{1,5})$""")

        fun parse(text: String): Parsed {
            val forwards = ArrayList<PortForward>()
            val invalid = ArrayList<String>()
            text.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                parseLine(line)?.let { forwards += it } ?: run { invalid += line }
            }
            return Parsed(forwards, invalid)
        }

        private fun parseLine(line: String): PortForward? {
            val type = line.trimStart('-').firstOrNull()?.uppercaseChar() ?: return null
            val rest = line.trimStart('-').drop(1).trim().trimStart(':').trim()
            fun port(s: String) = s.toIntOrNull()?.takeIf { it in 1..65535 }
            return when (type) {
                'D' -> port(rest)?.let { Dynamic(it) }
                'L', 'R' -> {
                    val m = HOST_PORT.matchEntire(rest) ?: return null
                    val first = port(m.groupValues[1]) ?: return null
                    val host = m.groupValues[2].removePrefix("[").removeSuffix("]")
                    val second = port(m.groupValues[3]) ?: return null
                    if (type == 'L') Local(first, host, second) else Remote(first, host, second)
                }
                else -> null
            }
        }
    }
}
