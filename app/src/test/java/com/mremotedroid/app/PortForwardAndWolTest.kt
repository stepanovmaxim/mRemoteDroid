package com.mremotedroid.app

import com.mremotedroid.app.launch.WakeOnLan
import com.mremotedroid.app.remote.ssh.PortForward
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortForwardAndWolTest {

    @Test
    fun parsesOpenSshStyleForwards() {
        val parsed = PortForward.parse(
            """
            L 8080:intranet:80
            -L3389:10.0.0.5:3389
            R 9000:localhost:22
            D 1080
            l 8443:[fe80::1]:443
            """.trimIndent()
        )
        assertEquals(
            listOf(
                PortForward.Local(8080, "intranet", 80),
                PortForward.Local(3389, "10.0.0.5", 3389),
                PortForward.Remote(9000, "localhost", 22),
                PortForward.Dynamic(1080),
                PortForward.Local(8443, "fe80::1", 443)
            ),
            parsed.forwards
        )
        assertEquals(emptyList<String>(), parsed.invalid)
    }

    @Test
    fun reportsInvalidLines() {
        val parsed = PortForward.parse("L 8080:host\nX 1:2:3\nD 70000; L 80:web:80")
        assertEquals(listOf(PortForward.Local(80, "web", 80)), parsed.forwards)
        assertEquals(listOf("L 8080:host", "X 1:2:3", "D 70000"), parsed.invalid)
    }

    @Test
    fun sendsMagicPacketToHost() {
        val listener = runCatching {
            java.net.DatagramSocket(java.net.InetSocketAddress("127.0.0.1", 9)).apply { soTimeout = 3000 }
        }.getOrNull() ?: return // port taken: nothing to check here
        listener.use {
            val mac = WakeOnLan.parseMac("01:23:45:67:89:ab")!!
            WakeOnLan.send(mac, "127.0.0.1")
            val packet = java.net.DatagramPacket(ByteArray(200), 200)
            it.receive(packet)
            assertEquals(102, packet.length)
            val data = packet.data.copyOf(packet.length)
            assertArrayEquals(ByteArray(6) { -1 }, data.copyOfRange(0, 6))
            for (i in 0 until 16) assertArrayEquals(mac, data.copyOfRange(6 + i * 6, 12 + i * 6))
        }
    }

    @Test
    fun parsesMacAddresses() {
        val expected = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        assertArrayEquals(expected, WakeOnLan.parseMac("00:11:22:33:44:55"))
        assertArrayEquals(expected, WakeOnLan.parseMac("00-11-22-33-44-55"))
        assertArrayEquals(expected, WakeOnLan.parseMac("0011.2233.4455"))
        assertArrayEquals(byteArrayOf(-1, -86, 0x0B, 0x0C, 0x0D, 0x0E), WakeOnLan.parseMac("ff:AA:0b:0c:0d:0e"))
        assertNull(WakeOnLan.parseMac("00:11:22:33:44"))
        assertNull(WakeOnLan.parseMac("zz:11:22:33:44:55"))
    }
}
