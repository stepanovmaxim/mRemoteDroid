package com.mremotedroid.app.remote.ssh

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Local ports that lead through an SSH connection, like `ssh -L` and `ssh -D`. They listen
 * on 127.0.0.1 only, so other devices on the network cannot use them. [session] gives the
 * SSH connection for each new client (a tunnel reconnects it there when it dropped).
 */
class Forwarder(private val name: String, private val session: () -> Session) : Closeable {
    private val servers = CopyOnWriteArrayList<ServerSocket>()
    private val clients: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var closed = false

    /** Forwards 127.0.0.1:[localPort] (0 = any free port) to [host]:[port]; returns the local port. */
    fun local(localPort: Int, host: String, port: Int): Int =
        listen(localPort) { client -> connect(client, host, port) }

    /** A SOCKS 4/4a/5 proxy on 127.0.0.1:[localPort]; returns the local port. */
    fun socks(localPort: Int): Int = listen(localPort, ::socks)

    private fun listen(localPort: Int, handle: (Socket) -> Unit): Int {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), localPort))
        servers += server
        if (closed) server.close()
        thread(isDaemon = true, name = "fwd-$name-${server.localPort}") {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                clients += client
                thread(isDaemon = true, name = "fwd-client") {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        Log.d(TAG, "forwarded connection ended", e)
                    } finally {
                        runCatching { client.close() }
                        clients -= client
                    }
                }
            }
        }
        return server.localPort
    }

    /** Pipes [client] to [host]:[port] until either side closes; [onOpen] runs once connected. */
    private fun connect(client: Socket, host: String, port: Int, onOpen: () -> Unit = {}) {
        val channel = session().openChannel("direct-tcpip") as ChannelDirectTCPIP
        channel.setHost(host)
        channel.setPort(port)
        channel.setOrgIPAddress(LOOPBACK)
        channel.setOrgPort(client.port)
        val fromRemote = channel.inputStream
        val toRemote = channel.outputStream
        channel.connect(SshConnect.CONNECT_TIMEOUT_MS)
        onOpen()
        client.tcpNoDelay = true
        thread(isDaemon = true, name = "fwd-up") {
            try {
                copy(client.getInputStream(), toRemote)
            } catch (_: IOException) {
            } finally {
                // like ssh: the client finished sending, the answer may still be coming
                runCatching { toRemote.close() }
            }
        }
        try {
            copy(fromRemote, client.getOutputStream())
        } catch (_: IOException) {
        } finally {
            channel.disconnect()
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val n = from.read(buffer)
            if (n < 0) break
            to.write(buffer, 0, n)
            to.flush()
        }
    }

    /** SOCKS handshake, then the same pipe as a fixed forward. Only CONNECT is supported. */
    private fun socks(client: Socket) {
        // unbuffered: what follows the handshake belongs to the forwarded connection
        val input = DataInputStream(client.getInputStream())
        val out = client.getOutputStream()
        fun reply(vararg b: Int) {
            out.write(ByteArray(b.size) { b[it].toByte() })
            out.flush()
        }
        when (input.readUnsignedByte()) {
            5 -> {
                input.readFully(ByteArray(input.readUnsignedByte())) // offered methods
                reply(5, 0) // no authentication
                input.readUnsignedByte() // version
                val command = input.readUnsignedByte()
                input.readUnsignedByte() // reserved
                val host = when (input.readUnsignedByte()) {
                    1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress
                    3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.US_ASCII)
                    4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress
                    else -> return reply(5, 8, 0, 1, 0, 0, 0, 0, 0, 0)
                }
                val port = input.readUnsignedShort()
                if (command != 1) return reply(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)
                var opened = false
                try {
                    connect(client, host!!, port) { opened = true; reply(5, 0, 0, 1, 0, 0, 0, 0, 0, 0) }
                } catch (e: Exception) {
                    if (!opened) runCatching { reply(5, 5, 0, 1, 0, 0, 0, 0, 0, 0) }
                    throw e
                }
            }
            4 -> {
                val command = input.readUnsignedByte()
                val port = input.readUnsignedShort()
                val ip = ByteArray(4).also(input::readFully)
                readCString(input) // user id
                // SOCKS 4a: 0.0.0.x means a host name follows
                val host = if (ip[0].toInt() == 0 && ip[1].toInt() == 0 && ip[2].toInt() == 0 && ip[3].toInt() != 0) {
                    readCString(input)
                } else {
                    InetAddress.getByAddress(ip).hostAddress!!
                }
                if (command != 1) return reply(0, 0x5B, 0, 0, 0, 0, 0, 0)
                var opened = false
                try {
                    connect(client, host, port) { opened = true; reply(0, 0x5A, 0, 0, 0, 0, 0, 0) }
                } catch (e: Exception) {
                    if (!opened) runCatching { reply(0, 0x5B, 0, 0, 0, 0, 0, 0) }
                    throw e
                }
            }
        }
    }

    private fun readCString(input: DataInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.readUnsignedByte()
            if (b == 0 || bytes.size() > 255) break
            bytes.write(b)
        }
        return bytes.toString("US-ASCII")
    }

    override fun close() {
        closed = true
        servers.forEach { runCatching { it.close() } }
        clients.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val TAG = "Forwarder"
        const val LOOPBACK = "127.0.0.1"
    }
}
