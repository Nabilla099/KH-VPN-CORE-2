package com.khvpn.app.vpn

import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionMonitor
import com.trilead.ssh2.DynamicPortForwarder
import com.trilead.ssh2.ServerHostKeyVerifier
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * SSH-2 (sshlib / Trilead lineage). Login ke SSH server lewat port lokal dnstt, lalu buka
 * dynamic port forward (SOCKS5) di 127.0.0.1:socksPort untuk tun2socks.
 *
 * Verifikasi host key sengaja tidak dilakukan: jalur ini sudah diautentikasi oleh public key
 * Noise milik dnstt-server, dan alamat SSH-nya hanya loopback.
 */
class SshTunnel {
    private var conn: Connection? = null
    private var fwd: DynamicPortForwarder? = null
    @Volatile var onLost: ((String) -> Unit)? = null

    /** @return null kalau sukses, atau pesan error. */
    fun open(
        host: String, port: Int, user: String, pass: String, socksPort: Int,
        compression: Boolean, timeoutMs: Long, isCancelled: () -> Boolean, retryDelayMs: Long = 1200,
    ): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = "timeout"
        while (System.currentTimeMillis() < deadline && !isCancelled()) {
            val c = Connection(host, port)
            try {
                if (compression) {
                    // API kompresi berbeda antar versi; pakai refleksi supaya tidak memutus build
                    runCatching { c.javaClass.getMethod("setCompression", Boolean::class.javaPrimitiveType).invoke(c, true) }
                }
                val left = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(3000)
                c.connect(ServerHostKeyVerifier { _, _, _, _ -> true }, left.coerceAtMost(30000), left)
                TunnelBus.i("SSH: handshake selesai, mengautentikasi $user")
                if (!c.authenticateWithPassword(user, pass)) {
                    c.close()
                    return "Login SSH ditolak (user/password salah atau akun kedaluwarsa)"
                }
                TunnelBus.i("SSH: login berhasil sebagai $user")
                c.addConnectionMonitor(ConnectionMonitor { reason -> onLost?.invoke(reason?.message ?: "koneksi terputus") })
                fwd = c.createDynamicPortForwarder(InetSocketAddress(InetAddress.getByName("127.0.0.1"), socksPort))
                conn = c
                TunnelBus.i("SSH: SOCKS5 aktif di 127.0.0.1:$socksPort")
                return null
            } catch (e: IOException) {
                runCatching { c.close() }
                last = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.lastOrNull() ?: e.javaClass.simpleName
                // dnstt belum siap / handshake DNS belum selesai: tunggu sebentar lalu ulangi
                TunnelBus.w("SSH: percobaan gagal ($last)")
                Thread.sleep(retryDelayMs)
            }
        }
        return "SSH gagal: $last"
    }

    @Volatile private var keepWarned = false

    /** Kirim paket keepalive SSH supaya server/CDN tidak memutus koneksi idle. Metode bergantung versi pustaka. */
    fun keepAlive() {
        val c = conn ?: return
        val ok = listOf("sendIgnorePacket", "ping").any { name ->
            runCatching { c.javaClass.getMethod(name).invoke(c) }.isSuccess
        }
        if (!ok && !keepWarned) {
            keepWarned = true
            TunnelBus.w("SSH: keepalive tidak tersedia di pustaka ini, koneksi idle bisa diputus server")
        }
    }

    fun close() {
        onLost = null
        runCatching { fwd?.close() }
        runCatching { conn?.close() }
        fwd = null; conn = null
    }
}
