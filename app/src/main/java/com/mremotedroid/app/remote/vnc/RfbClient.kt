package com.mremotedroid.app.remote.vnc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * A minimal RFB (VNC) client: protocol 3.3/3.7/3.8; security None, VNC password,
 * VeNCrypt with TLS (X509 None/Vnc/Plain) and Apple Remote Desktop; encodings Raw,
 * CopyRect, RRE, Hextile and ZRLE, plus the DesktopSize pseudo-encoding.
 *
 * The framebuffer is 32-bit 0xAARRGGBB, ready for an Android Bitmap.
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String?,
    private val listener: Listener
) {
    interface Listener {
        /** The framebuffer was (re)created: [pixels] is width*height ARGB. */
        fun onResize(width: Int, height: Int, pixels: IntArray, name: String)

        /** A region of the framebuffer changed. */
        fun onUpdate(x: Int, y: Int, width: Int, height: Int)

        fun onClipboard(text: String)

        /** Asked when the server wants a password and none is stored; null cancels. */
        fun requestPassword(): String?
    }

    class AuthException(message: String) : IOException(message)
    class UnsupportedSecurityException(val types: List<Int>) : IOException("security types $types")

    private lateinit var socket: Socket
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    var width = 0
        private set
    var height = 0
        private set
    @Volatile var pixels = IntArray(0)
        private set

    private val inflater = Inflater()
    private var zlibBuffer = ByteArray(0)

    /**
     * Connects, authenticates and processes server messages until the connection ends.
     * Through a tunnel, [connectHost]/[connectPort] are its local end.
     */
    fun run(connectHost: String = host, connectPort: Int = port) {
        socket = Socket()
        socket.connect(InetSocketAddress(connectHost, connectPort), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true
        attachStreams()
        val version = handshakeVersion()
        authenticate(version)
        // ClientInit: share the desktop with other viewers
        output.writeByte(1)
        output.flush()
        serverInit()
        setPixelFormat()
        setEncodings()
        requestUpdate(false)
        messageLoop()
    }

    private fun attachStreams() {
        input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
        output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 16 * 1024))
    }

    fun close() {
        closed = true
        writer.shutdownNow()
        runCatching { socket.close() }
    }

    // --- handshake ---

    private fun handshakeVersion(): Int {
        val bytes = ByteArray(12)
        input.readFully(bytes)
        val text = String(bytes, Charsets.US_ASCII)
        if (!text.startsWith("RFB ")) throw IOException("not a VNC server")
        val minor = text.substring(8, 11).toIntOrNull() ?: 3
        // 3.889 is Apple's 3.8 variant
        val chosen = when {
            minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        output.write("RFB 003.00$chosen\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        return chosen
    }

    private fun authenticate(version: Int) {
        val types: List<Int> = if (version == 3) {
            listOf(input.readInt())
        } else {
            val count = input.readUnsignedByte()
            if (count == 0) throw IOException(readReason())
            List(count) { input.readUnsignedByte() }
        }
        if (types.contains(0) || types.isEmpty()) throw IOException(readReason())

        val type = listOf(SEC_NONE, SEC_VNC, SEC_VENCRYPT, SEC_ARD).firstOrNull { it in types }
            ?: throw UnsupportedSecurityException(types)
        if (version != 3) {
            output.writeByte(type)
            output.flush()
        }
        when (type) {
            SEC_NONE -> if (version == 8) checkSecurityResult(version)
            SEC_VNC -> {
                vncAuth()
                checkSecurityResult(version)
            }
            SEC_VENCRYPT -> {
                veNCrypt()
                checkSecurityResult(version)
            }
            SEC_ARD -> {
                ardAuth()
                checkSecurityResult(version)
            }
        }
    }

    private fun checkSecurityResult(version: Int) {
        val result = input.readInt()
        if (result != 0) {
            val reason = if (version == 8) readReason() else ""
            throw AuthException(reason)
        }
    }

    private fun readReason(): String = runCatching {
        val len = input.readInt()
        if (len in 0..65536) ByteArray(len).also { input.readFully(it) }.toString(Charsets.UTF_8) else ""
    }.getOrDefault("")

    private fun secret(): String = password?.takeIf { it.isNotEmpty() }
        ?: listener.requestPassword() ?: throw AuthException("cancelled")

    /** DES challenge-response; the key bytes are bit-reversed, as in every VNC client. */
    private fun vncAuth() {
        val challenge = ByteArray(16)
        input.readFully(challenge)
        val key = ByteArray(8)
        val pw = secret().toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 8) {
            val b = if (i < pw.size) pw[i].toInt() else 0
            key[i] = (Integer.reverse(b and 0xFF) ushr 24).toByte()
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        output.write(cipher.doFinal(challenge))
        output.flush()
    }

    private fun veNCrypt() {
        val major = input.readUnsignedByte()
        val minor = input.readUnsignedByte()
        if (major != 0 || minor < 2) throw IOException("VeNCrypt $major.$minor")
        output.writeByte(0)
        output.writeByte(2)
        output.flush()
        if (input.readUnsignedByte() != 0) throw IOException("VeNCrypt version refused")
        val count = input.readUnsignedByte()
        val subtypes = List(count) { input.readInt() }
        // Anonymous TLS (TLSNone/TLSVnc/TLSPlain) needs cipher suites Android lacks.
        val preferred = if (username.isNotBlank()) {
            listOf(X509_PLAIN, X509_VNC, X509_NONE, SEC_VNC)
        } else {
            listOf(X509_VNC, X509_NONE, X509_PLAIN, SEC_VNC)
        }
        val chosen = preferred.firstOrNull { it in subtypes } ?: throw UnsupportedSecurityException(subtypes)
        output.writeInt(chosen)
        output.flush()
        if (chosen != SEC_VNC && input.readUnsignedByte() != 1) throw IOException("VeNCrypt subtype refused")
        if (chosen != SEC_VNC) startTls()
        when (chosen) {
            SEC_VNC, X509_VNC -> vncAuth()
            X509_PLAIN -> {
                val user = username.toByteArray(Charsets.UTF_8)
                val pass = secret().toByteArray(Charsets.UTF_8)
                output.writeInt(user.size)
                output.writeInt(pass.size)
                output.write(user)
                output.write(pass)
                output.flush()
            }
        }
    }

    /**
     * Like the RDP sessions (cert=ignore), the server certificate is not verified:
     * VNC servers almost always use self-signed certificates.
     */
    private fun startTls() {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trustAll), SecureRandom())
        val tls = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        tls.useClientMode = true
        tls.startHandshake()
        socket = tls
        attachStreams()
    }

    /** Apple Remote Desktop (type 30): Diffie-Hellman, then AES-128-ECB of user and password. */
    private fun ardAuth() {
        val generator = input.readUnsignedShort()
        val keyLength = input.readUnsignedShort()
        val prime = ByteArray(keyLength).also { input.readFully(it) }
        val serverPublic = ByteArray(keyLength).also { input.readFully(it) }
        val p = BigInteger(1, prime)
        val private = BigInteger(keyLength * 8 - 1, SecureRandom())
        val public = BigInteger.valueOf(generator.toLong()).modPow(private, p)
        val shared = BigInteger(1, serverPublic).modPow(private, p)
        val key = MessageDigest.getInstance("MD5").digest(toFixed(shared, keyLength))

        val credentials = ByteArray(128)
        SecureRandom().nextBytes(credentials)
        fun put(text: String, offset: Int) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val n = minOf(63, bytes.size)
            System.arraycopy(bytes, 0, credentials, offset, n)
            credentials[offset + n] = 0
        }
        put(username, 0)
        put(secret(), 64)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        output.write(cipher.doFinal(credentials))
        output.write(toFixed(public, keyLength))
        output.flush()
    }

    private fun toFixed(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
            else -> ByteArray(length - raw.size) + raw
        }
    }

    private fun serverInit() {
        val w = input.readUnsignedShort()
        val h = input.readUnsignedShort()
        input.skipBytes(16) // server pixel format; we set our own
        val nameLength = input.readInt()
        val name = if (nameLength in 0..4096) ByteArray(nameLength).also { input.readFully(it) }
            .toString(Charsets.UTF_8) else ""
        resize(w, h, name)
    }

    private var desktopName = ""

    private fun resize(w: Int, h: Int, name: String = desktopName) {
        desktopName = name
        width = w
        height = h
        pixels = IntArray(w * h) { OPAQUE }
        listener.onResize(w, h, pixels, name)
    }

    /** 32 bpp, depth 24, little-endian, red at bit 16: the ARGB layout without alpha. */
    private fun setPixelFormat() {
        output.writeByte(0)
        output.write(ByteArray(3))
        output.writeByte(32)
        output.writeByte(24)
        output.writeByte(0) // little-endian
        output.writeByte(1) // true colour
        output.writeShort(255)
        output.writeShort(255)
        output.writeShort(255)
        output.writeByte(16)
        output.writeByte(8)
        output.writeByte(0)
        output.write(ByteArray(3))
        output.flush()
    }

    private fun setEncodings() {
        val encodings = intArrayOf(ENC_ZRLE, ENC_HEXTILE, ENC_RRE, ENC_COPYRECT, ENC_RAW, ENC_DESKTOP_SIZE)
        output.writeByte(2)
        output.writeByte(0)
        output.writeShort(encodings.size)
        encodings.forEach { output.writeInt(it) }
        output.flush()
    }

    private fun requestUpdate(incremental: Boolean) {
        output.writeByte(3)
        output.writeByte(if (incremental) 1 else 0)
        output.writeShort(0)
        output.writeShort(0)
        output.writeShort(width)
        output.writeShort(height)
        output.flush()
    }

    // --- server messages ---

    private fun messageLoop() {
        while (!closed) {
            when (val type = input.readUnsignedByte()) {
                0 -> {
                    val resized = framebufferUpdate()
                    synchronized(output) { requestUpdate(!resized) }
                }
                1 -> { // SetColourMapEntries: not used with true colour
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                    val count = input.readUnsignedShort()
                    input.skipBytes(count * 6)
                }
                2 -> Unit // Bell
                3 -> {
                    input.skipBytes(3)
                    val len = input.readInt()
                    if (len !in 0..MAX_CLIPBOARD) throw IOException("clipboard of $len bytes")
                    val text = ByteArray(len).also { input.readFully(it) }.toString(Charsets.ISO_8859_1)
                    listener.onClipboard(text)
                }
                else -> throw IOException("unknown server message $type")
            }
        }
    }

    /** Returns true if the desktop size changed, which needs a full update. */
    private fun framebufferUpdate(): Boolean {
        input.readUnsignedByte()
        val rects = input.readUnsignedShort()
        repeat(rects) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val w = input.readUnsignedShort()
            val h = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENC_RAW -> raw(x, y, w, h)
                ENC_COPYRECT -> copyRect(x, y, w, h)
                ENC_RRE -> rre(x, y, w, h)
                ENC_HEXTILE -> hextile(x, y, w, h)
                ENC_ZRLE -> zrle(x, y, w, h)
                ENC_DESKTOP_SIZE -> {
                    resize(w, h)
                    return true
                }
                else -> throw IOException("unexpected encoding $encoding")
            }
            listener.onUpdate(x, y, w, h)
        }
        return false
    }

    private fun checkRect(x: Int, y: Int, w: Int, h: Int) {
        if (x + w > width || y + h > height) throw IOException("rectangle outside the desktop")
    }

    private fun readPixel(): Int {
        val b = input.readUnsignedByte()
        val g = input.readUnsignedByte()
        val r = input.readUnsignedByte()
        input.readUnsignedByte()
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private fun fill(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val px = pixels
        for (row in y until y + h) {
            val start = row * width + x
            java.util.Arrays.fill(px, start, start + w, color)
        }
    }

    private var rowBuffer = ByteArray(0)

    private fun raw(x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        if (rowBuffer.size < w * 4) rowBuffer = ByteArray(w * 4)
        val px = pixels
        for (row in y until y + h) {
            input.readFully(rowBuffer, 0, w * 4)
            var o = row * width + x
            var i = 0
            repeat(w) {
                px[o++] = OPAQUE or ((rowBuffer[i + 2].toInt() and 0xFF) shl 16) or
                    ((rowBuffer[i + 1].toInt() and 0xFF) shl 8) or (rowBuffer[i].toInt() and 0xFF)
                i += 4
            }
        }
    }

    private fun copyRect(x: Int, y: Int, w: Int, h: Int) {
        val sx = input.readUnsignedShort()
        val sy = input.readUnsignedShort()
        checkRect(x, y, w, h)
        checkRect(sx, sy, w, h)
        val px = pixels
        if (sy < y) {
            for (row in h - 1 downTo 0) System.arraycopy(px, (sy + row) * width + sx, px, (y + row) * width + x, w)
        } else {
            for (row in 0 until h) System.arraycopy(px, (sy + row) * width + sx, px, (y + row) * width + x, w)
        }
    }

    private fun rre(x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        val count = input.readInt()
        fill(x, y, w, h, readPixel())
        repeat(count) {
            val color = readPixel()
            val sx = input.readUnsignedShort()
            val sy = input.readUnsignedShort()
            val sw = input.readUnsignedShort()
            val sh = input.readUnsignedShort()
            if (sx + sw <= w && sy + sh <= h) fill(x + sx, y + sy, sw, sh, color)
        }
    }

    private fun hextile(x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        var background = OPAQUE
        var foreground = OPAQUE or 0xFFFFFF
        var ty = y
        while (ty < y + h) {
            val th = minOf(16, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(16, x + w - tx)
                val mask = input.readUnsignedByte()
                if (mask and 1 != 0) {
                    raw(tx, ty, tw, th)
                } else {
                    if (mask and 2 != 0) background = readPixel()
                    fill(tx, ty, tw, th, background)
                    if (mask and 4 != 0) foreground = readPixel()
                    if (mask and 8 != 0) {
                        val subrects = input.readUnsignedByte()
                        val coloured = mask and 16 != 0
                        repeat(subrects) {
                            val color = if (coloured) readPixel() else foreground
                            val xy = input.readUnsignedByte()
                            val wh = input.readUnsignedByte()
                            val sx = xy shr 4
                            val sy = xy and 15
                            val sw = (wh shr 4) + 1
                            val sh = (wh and 15) + 1
                            if (sx + sw <= tw && sy + sh <= th) fill(tx + sx, ty + sy, sw, sh, color)
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
    }

    // ZRLE: one zlib stream for the whole connection, 64x64 tiles of 3-byte CPIXELs

    private var zPos = 0
    private var zLen = 0

    private fun zrle(x: Int, y: Int, w: Int, h: Int) {
        checkRect(x, y, w, h)
        val length = input.readInt()
        if (length !in 0..MAX_ZRLE) throw IOException("ZRLE block of $length bytes")
        val compressed = ByteArray(length).also { input.readFully(it) }
        inflater.setInput(compressed)
        // inflate everything this rectangle produced
        var size = 0
        val maxOut = w * h * 4 + (w / 64 + 1) * (h / 64 + 1) * 64 + 1024
        if (zlibBuffer.size < maxOut) zlibBuffer = ByteArray(maxOut)
        while (true) {
            if (size == zlibBuffer.size) zlibBuffer = zlibBuffer.copyOf(zlibBuffer.size * 2)
            val n = inflater.inflate(zlibBuffer, size, zlibBuffer.size - size)
            size += n
            if (n == 0 && (inflater.needsInput() || inflater.finished() || inflater.needsDictionary())) break
        }
        zPos = 0
        zLen = size

        val palette = IntArray(128)
        var ty = y
        while (ty < y + h) {
            val th = minOf(64, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(64, x + w - tx)
                val sub = zByte()
                when {
                    sub == 0 -> for (row in 0 until th) {
                        var o = (ty + row) * width + tx
                        repeat(tw) { pixels[o++] = cpixel() }
                    }
                    sub == 1 -> fill(tx, ty, tw, th, cpixel())
                    sub in 2..16 -> {
                        repeat(sub) { palette[it] = cpixel() }
                        val bits = if (sub == 2) 1 else if (sub <= 4) 2 else 4
                        for (row in 0 until th) {
                            var o = (ty + row) * width + tx
                            var byte = 0
                            var left = 0
                            repeat(tw) {
                                if (left == 0) {
                                    byte = zByte()
                                    left = 8
                                }
                                left -= bits
                                pixels[o++] = palette[(byte shr left) and ((1 shl bits) - 1)]
                            }
                        }
                    }
                    sub == 128 -> rle(tx, ty, tw, th) { cpixel() }
                    sub >= 130 -> {
                        repeat(sub - 128) { palette[it] = cpixel() }
                        rlePalette(tx, ty, tw, th, palette)
                    }
                    else -> throw IOException("ZRLE subencoding $sub")
                }
                tx += 64
            }
            ty += 64
        }
    }

    private fun zByte(): Int {
        if (zPos >= zLen) throw IOException("ZRLE data ended early")
        return zlibBuffer[zPos++].toInt() and 0xFF
    }

    private fun cpixel(): Int {
        val b = zByte()
        val g = zByte()
        val r = zByte()
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    private fun runLength(): Int {
        var length = 1
        while (true) {
            val b = zByte()
            length += b
            if (b != 255) return length
        }
    }

    private inline fun rle(tx: Int, ty: Int, tw: Int, th: Int, color: () -> Int) {
        var i = 0
        val total = tw * th
        while (i < total) {
            val c = color()
            val run = runLength()
            repeat(run) {
                if (i < total) pixels[(ty + i / tw) * width + tx + i % tw] = c
                i++
            }
        }
    }

    private fun rlePalette(tx: Int, ty: Int, tw: Int, th: Int, palette: IntArray) {
        var i = 0
        val total = tw * th
        while (i < total) {
            val index = zByte()
            val c = palette[index and 127]
            val run = if (index and 128 != 0) runLength() else 1
            repeat(run) {
                if (i < total) pixels[(ty + i / tw) * width + tx + i % tw] = c
                i++
            }
        }
    }

    // --- client messages (any thread) ---

    private fun send(block: DataOutputStream.() -> Unit) {
        if (closed) return
        runCatching {
            writer.execute {
                runCatching {
                    synchronized(output) {
                        output.block()
                        output.flush()
                    }
                }
            }
        }
    }

    fun pointer(x: Int, y: Int, buttons: Int) = send {
        writeByte(5)
        writeByte(buttons)
        writeShort(x.coerceIn(0, maxOf(0, width - 1)))
        writeShort(y.coerceIn(0, maxOf(0, height - 1)))
    }

    fun key(keysym: Int, down: Boolean) = send {
        writeByte(4)
        writeByte(if (down) 1 else 0)
        writeShort(0)
        writeInt(keysym)
    }

    fun clipboard(text: String) = send {
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        writeByte(6)
        write(ByteArray(3))
        writeInt(bytes.size)
        write(bytes)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val OPAQUE = 0xFF000000.toInt()
        private const val MAX_CLIPBOARD = 1 shl 20
        private const val MAX_ZRLE = 64 shl 20

        const val SEC_NONE = 1
        const val SEC_VNC = 2
        const val SEC_VENCRYPT = 19
        const val SEC_ARD = 30
        private const val X509_NONE = 260
        private const val X509_VNC = 261
        private const val X509_PLAIN = 262

        private const val ENC_RAW = 0
        private const val ENC_COPYRECT = 1
        private const val ENC_RRE = 2
        private const val ENC_HEXTILE = 5
        private const val ENC_ZRLE = 16
        private const val ENC_DESKTOP_SIZE = -223
    }
}
