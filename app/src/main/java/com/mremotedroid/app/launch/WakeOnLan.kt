package com.mremotedroid.app.launch

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Wake-on-LAN "magic packets": 6 x 0xFF, then the MAC address 16 times. */
object WakeOnLan {
    private val SEPARATORS = Regex("[\\s:.-]")

    /** The six bytes of [mac] ("00:11:22:33:44:55", "00-11-…", "0011.2233.4455"), or null. */
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.replace(SEPARATORS, "")
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /**
     * Sends the packet to the broadcast address of every local IPv4 network, to
     * 255.255.255.255, and to [host] itself (which helps when the router forwards
     * directed broadcasts). Blocking: call off the main thread. Returns the packets sent.
     */
    fun send(mac: ByteArray, host: String?): Int {
        val payload = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) payload[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(mac, 0, payload, 6 + i * 6, 6)

        val targets = LinkedHashSet<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .filter { it.address is Inet4Address }
                .mapNotNullTo(targets) { it.broadcast }
        }
        targets += InetAddress.getByName("255.255.255.255")
        host?.takeIf { it.isNotBlank() }?.let { h -> runCatching { InetAddress.getByName(h) }.getOrNull() }
            ?.let { targets += it }

        var sent = 0
        DatagramSocket().use { socket ->
            socket.broadcast = true
            targets.forEach { address ->
                PORTS.forEach { port ->
                    if (runCatching { socket.send(DatagramPacket(payload, payload.size, address, port)) }.isSuccess) sent++
                }
            }
        }
        return sent
    }

    private val PORTS = intArrayOf(9, 7)
}
