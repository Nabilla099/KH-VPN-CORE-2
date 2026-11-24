package com.khvpn.app.vpn

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Forwarder TCP lokal untuk metode SSH + WebSocket dan SSH + HTTP payload.
 *
 * Aplikasi membuka 127.0.0.1:localPort. Tiap koneksi masuk (dari klien SSH) diteruskan ke server:
 *   TCP (opsional lewat HTTP proxy) -> TLS opsional dengan SNI bebas -> kirim payload HTTP
 *   -> baca balasan (101/2xx) -> sisanya dipipa mentah dua arah (SSH ada di dalamnya).
 *
 * Ini tipe "WebSocket" yang umum dipakai server SSH-WS: setelah "101 Switching Protocols" aliran
 * menjadi TCP mentah ke sshd, tanpa framing WebSocket.
 *
 * Verifikasi sertifikat TLS sengaja dimatikan: SNI sering dipalsukan (domain bug/CDN), dan SSH
 * di dalamnya tetap terenkripsi sendiri.
 */
class PayloadTunnel(
    private val connectHost: String,
    private val connectPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val tls: Boolean,
    private val sni: String,
    private val payloadTemplate: String,
    private val websocketDefault: Boolean,
    private val wsHost: String,
    private val wsPath: String,
) {
    private var server: ServerSocket? = null
    @Volatile private var closed = false
    private val live = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    /** @return null kalau listener siap, atau pesan error. */
    fun start(localPort: Int): String? = try {
        val ss = ServerSocket()
        ss.reuseAddress = true // harus sebelum bind, kalau tidak sambung ulang bisa gagal "Address already in use"
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort), 16)
        server = ss
        Thread({
            while (!closed) {
                val c = try { ss.accept() } catch (_: IOException) { break }
                Thread({ handle(c) }, "payload-conn").apply { isDaemon = true }.start()
            }
        }, "payload-accept").apply { isDaemon = true }.start()
        null
    } catch (e: IOException) {
        "Port lokal $localPort tidak bisa dipakai: ${e.message}"
    }

    fun close() {
        closed = true
        runCatching { server?.close() }
        synchronized(live) { live.toList() }.forEach { runCatching { it.close() } }
        live.clear()
    }

    // ------------------------------------------------------------------ satu koneksi

    private fun handle(client: Socket) {
        live += client
        var up: Socket? = null
        try {
            client.tcpNoDelay = true
            val u = openUpstream()
            val s = u.sock
            up = s; live += s
            if (u.framed) {
                // Server memakai framing WebSocket penuh (RFC 6455): bungkus/buka bingkai di sini
                TunnelBus.i("Payload: terdeteksi framing WebSocket, memakai mode bingkai")
                val sout = s.getOutputStream()
                val t = Thread({ framedDown(SequenceInputStream(ByteArrayInputStream(u.leftover), s.getInputStream()), client.getOutputStream(), sout, s, client) }, "payload-down")
                t.isDaemon = true; t.start()
                framedUp(client.getInputStream(), sout, client, s)
            } else {
                if (u.leftover.isNotEmpty()) {
                    if (!u.leftover.startsWith("SSH-")) TunnelBus.w("Payload: data setelah header bukan banner SSH: " + preview(u.leftover))
                    client.getOutputStream().apply { write(u.leftover); flush() }
                }
                val t = Thread({ pipe(s.getInputStream(), client.getOutputStream(), s, client) }, "payload-down")
                t.isDaemon = true; t.start()
                pipe(client.getInputStream(), s.getOutputStream(), client, s)
            }
        } catch (e: Exception) {
            if (!closed) TunnelBus.w("Payload: ${e.message ?: e.javaClass.simpleName}")
            runCatching { client.close() }
            runCatching { up?.close() }
        } finally {
            live -= client; up?.let { live -= it }
        }
    }

    private fun pipe(i: InputStream, o: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n); o.flush()
            }
        } catch (_: IOException) {
        } finally {
            runCatching { a.close() }; runCatching { b.close() }
        }
    }

    private class Upstream(val sock: Socket, val leftover: ByteArray, val framed: Boolean)

    private fun ByteArray.startsWith(p: String): Boolean =
        size >= p.length && p.indices.all { this[it] == p[it].code.toByte() }

    private fun preview(b: ByteArray): String =
        b.take(48).joinToString("") { c -> val v = c.toInt() and 0xFF; if (v in 32..126) v.toChar().toString() else "." }

    // ------------------------------------------------------------------ framing WebSocket (klien -> server wajib bermask)

    private val rnd = SecureRandom()

    private fun writeFrame(out: OutputStream, opcode: Int, data: ByteArray, off: Int, len: Int) {
        val head = ByteArrayOutputStream(14)
        head.write(0x80 or opcode)
        when {
            len < 126 -> head.write(0x80 or len)
            len <= 0xFFFF -> { head.write(0x80 or 126); head.write(len shr 8); head.write(len and 0xFF) }
            else -> { head.write(0x80 or 127); for (i in 7 downTo 0) head.write((len.toLong() shr (8 * i)).toInt() and 0xFF) }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        head.write(mask)
        val body = ByteArray(len) { (data[off + it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(out) { out.write(head.toByteArray()); out.write(body); out.flush() }
    }

    private fun framedUp(i: InputStream, out: OutputStream, a: Socket, b: Socket) {
        val buf = ByteArray(8 * 1024)
        try {
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                writeFrame(out, 2, buf, 0, n)
            }
        } catch (_: IOException) {
        } finally { runCatching { a.close() }; runCatching { b.close() } }
    }

    private fun framedDown(ins: InputStream, toClient: OutputStream, toServer: OutputStream, a: Socket, b: Socket) {
        val din = DataInputStream(ins)
        try {
            while (true) {
                val b0 = din.readUnsignedByte()
                val b1 = din.readUnsignedByte()
                val op = b0 and 0x0F
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = din.readUnsignedShort().toLong() else if (len == 127L) len = din.readLong()
                if (b1 and 0x80 != 0) din.skipBytes(4)           // server seharusnya tidak memask
                if (len < 0 || len > 4 * 1024 * 1024) throw IOException("bingkai WebSocket tidak wajar")
                val data = ByteArray(len.toInt()); din.readFully(data)
                when (op) {
                    0, 1, 2 -> { toClient.write(data); toClient.flush() }
                    8 -> break
                    9 -> writeFrame(toServer, 10, data, 0, data.size)   // ping -> pong
                    else -> {}
                }
            }
        } catch (_: IOException) {
        } finally { runCatching { a.close() }; runCatching { b.close() } }
    }

    private fun openUpstream(): Upstream {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.keepAlive = true
        raw.connect(InetSocketAddress(connectHost, connectPort), 12_000)
        var sock: Socket = raw
        if (tls) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<javax.net.ssl.TrustManager>(TrustAll), SecureRandom())
            val name = sni.ifBlank { wsHost.ifBlank { targetHost } }
            // host yang diberikan di sini dipakai sebagai SNI
            val ssl = ctx.socketFactory.createSocket(raw, name, connectPort, true) as SSLSocket
            ssl.soTimeout = 15_000
            ssl.startHandshake()
            sock = ssl
        }
        sock.soTimeout = 15_000
        val out = sock.getOutputStream()
        val chunks = expand(buildPayload()).split("[split]", ignoreCase = true)
        chunks.forEachIndexed { idx, c ->
            if (c.isNotEmpty()) { out.write(c.toByteArray(Charsets.ISO_8859_1)); out.flush() }
            if (idx < chunks.lastIndex) Thread.sleep(300)
        }
        val leftover = readResponse(sock)
        sock.soTimeout = 0
        val framed = leftover.isNotEmpty() && (leftover[0] == 0x81.toByte() || leftover[0] == 0x82.toByte())
        return Upstream(sock, leftover, framed)
    }

    // ------------------------------------------------------------------ payload

    private fun buildPayload(): String {
        if (payloadTemplate.isNotBlank()) return payloadTemplate
        check(websocketDefault) { "payload kosong" }
        val key = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val host = wsHost.ifBlank { targetHost }
        val path = wsPath.ifBlank { "/" }
        return "GET $path HTTP/1.1[crlf]Host: $host[crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf]" +
            "Sec-WebSocket-Key: $key[crlf]Sec-WebSocket-Version: 13[crlf]User-Agent: [ua][crlf][crlf]"
    }

    private fun expand(t: String): String {
        var s = t.replace("\r\n", "\n").replace("\n", "\r\n")   // baris baru dari kolom teks -> CRLF
            .replace("\\r", "\r").replace("\\n", "\n")            // penulisan \r\n manual
        val map = linkedMapOf(
            "[crlf]" to "\r\n", "[cr]" to "\r", "[lf]" to "\n",
            "[host_port]" to "$targetHost:$targetPort", "[host]" to targetHost, "[port]" to targetPort.toString(),
            "[protocol]" to "HTTP/1.1", "[ua]" to UA,
        )
        map.forEach { (k, v) -> s = s.replace(k, v, ignoreCase = true) }
        return s
    }

    /**
     * Baca balasan server. Server payload sering membalas LEBIH DARI SATU header HTTP berturut-turut
     * (mis. "200 Connection established" lalu "101 Switching Protocols"); semuanya dibuang di sini supaya
     * klien SSH hanya melihat banner SSH. @return byte setelah header (banner SSH / bingkai WebSocket / kosong).
     */
    private fun readResponse(sock: Socket): ByteArray {
        val ins = sock.getInputStream()
        val tmp = ByteArray(4096)
        var buf = ByteArray(0)
        var headers = 0
        while (true) {
            val s = String(buf, Charsets.ISO_8859_1)
            if (s.startsWith("SSH-")) return buf
            if (s.length >= 5 && !s.startsWith("HTTP/")) return buf      // bukan HTTP: banner SSH / bingkai WS / aliran mentah
            val end = s.indexOf("\r\n\r\n")
            if (end >= 0) {
                val first = s.substringBefore("\r\n")
                val code = first.split(" ").getOrNull(1)?.toIntOrNull()
                TunnelBus.i("Payload: server membalas \"${first.take(80)}\"")
                if (code == null || !(code == 101 || code in 200..299)) {
                    throw IOException("server menolak payload: ${first.take(80)}")
                }
                headers++
                buf = buf.copyOfRange(end + 4, buf.size)
                if (buf.isEmpty()) {
                    // Tunggu data pertama dari server (banner SSH, atau bingkai WebSocket yang membungkus banner).
                    // Data ini menentukan mode raw atau bingkai; menunggu terlalu singkat di jaringan seluler yang lambat
                    // membuat bingkai WebSocket terbaca sebagai SSH rusak, login gagal, lalu koneksi diulang terus.
                    sock.soTimeout = FIRST_DATA_WAIT_MS
                    val n = try { ins.read(tmp) } catch (_: SocketTimeoutException) { sock.soTimeout = 15_000; return buf }
                    sock.soTimeout = 15_000
                    if (n < 0) throw IOException("server menutup koneksi setelah membalas payload")
                    buf = tmp.copyOf(n)
                }
                continue
            }
            if (buf.size > 16 * 1024) throw IOException("balasan HTTP terlalu panjang")
            val n = ins.read(tmp)
            if (n < 0) throw IOException("server menutup koneksi sebelum membalas payload")
            buf += tmp.copyOf(n)
        }
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val FIRST_DATA_WAIT_MS = 8_000
        const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
