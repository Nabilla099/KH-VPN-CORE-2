package com.khvpn.app.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.khvpn.app.KhApp
import com.khvpn.app.MainActivity
import com.khvpn.app.R
import com.khvpn.app.data.Method
import com.khvpn.app.data.Profile
import com.khvpn.app.data.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class KhVpnService : VpnService() {

    private data class Outcome(val connected: Boolean, val reason: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    @Volatile private var userStop = false

    private var dnstt: Proc? = null
    private var tun: Proc? = null
    private var ssh: SshTunnel? = null
    private var xray: Proc? = null
    private var payload: PayloadTunnel? = null
    private var vpn: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val store get() = (application as KhApp).store

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        scope.launch {
            TunnelBus.state.collect { st -> nm.notify(NOTIF_ID, buildNotification(st, TunnelBus.speed.value)) }
        }
        // Ukur kecepatan hanya selama terhubung; berhenti otomatis saat putus
        scope.launch {
            TunnelBus.state.map { it.phase == Phase.Connected }.distinctUntilChanged()
                .collectLatest { on -> if (on) sampleSpeed(nm) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Wajib dipanggil cepat setelah startForegroundService(), termasuk untuk ACTION_STOP
        startForegroundCompat(buildNotification(TunnelBus.state.value))
        when (intent?.action) {
            ACTION_STOP -> { userStop = true; shutdown() }
            else -> {
                if (runJob?.isActive == true) return START_NOT_STICKY
                userStop = false
                val id = intent?.getStringExtra(EXTRA_PROFILE)
                runJob = scope.launch { supervise(id) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() { userStop = true; shutdown(); super.onRevoke() }

    override fun onDestroy() {
        userStop = true
        runBlockingTeardown()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ alur utama

    private suspend fun supervise(profileId: String?) {
        val profile = store.profiles.value.firstOrNull { it.id == profileId } ?: store.selected
        if (profile == null || !profile.isValid) { fail("Profil belum lengkap atau tidak valid untuk metode ${profile?.method?.short ?: ""}".trim()); return }

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        acquireLocks()
        var attempt = 0
        var everConnected = false
        try {
            while (!userStop) {
                val t0 = SystemClock.elapsedRealtime()
                val out = runOnce(profile)
                teardown()
                if (userStop) break
                val s = store.settings.value
                if (out.connected) everConnected = true
                // sesi yang sempat stabil >= 30 detik dianggap sehat: jeda ulang dihitung dari awal
                if (out.connected && SystemClock.elapsedRealtime() - t0 >= 30_000) attempt = 0
                // Tanpa batas setelah pernah tersambung. Sebelum pernah tersambung, 3 percobaan lalu berhenti
                // supaya konfigurasi yang salah tidak berputar selamanya. Error fatal (password salah, dsb) langsung berhenti.
                if (!s.autoReconnect || isFatal(out.reason) || (!everConnected && attempt >= 2)) { fail(out.reason); return }
                attempt++
                TunnelBus.w("${out.reason} — menyambung ulang (#$attempt)")
                TunnelBus.set(Phase.StartingDns, "Menyambung ulang (#$attempt)…")
                delay(minOf(3000L + (attempt - 1) * 2000L, 15_000L))
                if (cm.activeNetwork == null) {
                    TunnelBus.set(Phase.StartingDns, "Menunggu jaringan…")
                    while (!userStop && cm.activeNetwork == null) delay(2000)
                }
            }
        } finally {
            withContext(NonCancellable) { teardown(); releaseLocks() }
        }
        if (userStop) shutdown()
    }

    /** Error yang tidak akan membaik dengan mencoba lagi. */
    private fun isFatal(r: String) = r.startsWith("Login SSH ditolak") || r.startsWith("Izin VPN") || r.startsWith("Gagal membuat antarmuka VPN") || r.startsWith("Port lokal") ||
        r.startsWith("Binary") || r.startsWith("Link V2Ray tidak valid") || r.contains("tidak ada untuk ABI")

    /** Hasil tahap "buka jalur": label rute kalau sukses, atau pesan error. */
    private data class Linked(val label: String?, val err: String = "", val routeKey: String? = null)

    private suspend fun runOnce(p: Profile): Outcome = coroutineScope {
        val s = store.settings.value
        val lost = CompletableDeferred<String>()
        if (!Natives.tun2socks(this@KhVpnService).exists()) return@coroutineScope Outcome(false, "Binary tun2socks tidak ada untuk ABI perangkat ini")

        val linked = when (p.method) {
            Method.DNSTT -> connectDnstt(p, s)
            Method.SSH_WS, Method.HTTP_PAYLOAD -> connectPayload(p, s)
            Method.V2RAY -> connectV2ray(p, s)
        }
        val label = linked.label ?: return@coroutineScope Outcome(false, linked.err)
        linked.routeKey?.let { store.setLastRoute(p.id, it) }

        // ---- VPN + tun2socks
        TunnelBus.set(Phase.EstablishingVpn, "Membuat antarmuka VPN", label)
        val fd = establishVpnWithRetry(s)
        if (fd == null) {
            // Bedakan "izin belum diberikan" dari kegagalan lain, lalu minta izin lewat UI
            return@coroutineScope if (VpnService.prepare(this@KhVpnService) != null) {
                TunnelBus.requestVpnPermission()
                Outcome(false, "Izin VPN belum diberikan. Setujui dialog izin VPN, aplikasi akan menyambung otomatis")
            } else Outcome(false, "Gagal membuat antarmuka VPN: ${lastVpnError ?: "sistem menolak (mungkin VPN lain sedang aktif)"}")
        }
        vpn = fd
        val sock = File(noBackupFilesDir, "t2s.sock")
        val t2s = runCatching { Tun2Socks.start(this@KhVpnService, s, p, sock) }.getOrElse {
            return@coroutineScope Outcome(false, "gagal menjalankan tun2socks: ${it.message}")
        }
        tun = t2s
        runCatching { Tun2Socks.sendFd(sock, fd.fileDescriptor) }.onFailure {
            delay(300) // beri waktu log keluaran tun2socks tampil
            val code = t2s.exitCode()
            val why = if (code != null) "tun2socks berhenti (kode $code), lihat baris 'tun2socks:' di atas" else it.message
            return@coroutineScope Outcome(false, "gagal mengirim fd TUN ke tun2socks: $why")
        }
        delay(800)
        if (!t2s.alive()) return@coroutineScope Outcome(false, "tun2socks berhenti (kode ${t2s.exitCode()}), cek log")

        val upAt = SystemClock.elapsedRealtime()
        val sshLost = Channel<String>(Channel.CONFLATED)
        ssh?.onLost = { sshLost.trySend(it) }
        TunnelBus.set(Phase.Connected, "", label)
        TunnelBus.i("Terhubung via $label")

        // ---- pantau
        val monitors = buildList {
            dnstt?.let { d -> add(launch { while (isActive && d.alive()) delay(1000); lost.complete("proses dnstt berhenti") }) }
            xray?.let { x -> add(launch { while (isActive && x.alive()) delay(1000); lost.complete("proses xray berhenti") }) }
            add(launch { while (isActive && t2s.alive()) delay(1000); lost.complete("proses tun2socks berhenti") })
            if (ssh != null) {
                val gap = if (p.method == Method.DNSTT) 25_000L else 15_000L
                add(launch { while (isActive) { delay(gap); ssh?.keepAlive() } })
                // Login ulang cepat TANPA memutus VPN: tunnel (dnstt/payload) masih hidup, hanya sesi SSH yang mati.
                // Status tetap "Terhubung", jadi tidak ada lagi bolak-balik putus/sambung yang terlihat.
                add(launch {
                    for (why in sshLost) {
                        TunnelBus.w("SSH terputus: $why — login ulang tanpa memutus VPN")
                        ssh?.close(); ssh = null
                        var ok = false
                        for (i in 1..3) {
                            if (userStop || !isActive) break
                            val t = SshTunnel()
                            val err = t.open(
                                "127.0.0.1", s.localPort, p.sshUser, p.sshPass, s.socksPort, s.sshCompression,
                                timeoutMs = 25_000, isCancelled = { userStop }, retryDelayMs = 2000,
                            )
                            if (err == null) {
                                ssh = t; t.onLost = { sshLost.trySend(it) }; ok = true
                                TunnelBus.i("SSH: tersambung kembali (percobaan $i)")
                                break
                            }
                            t.close()
                            if (err.startsWith("Login SSH ditolak")) break
                        }
                        if (!ok) { lost.complete("SSH terputus: $why"); break }
                    }
                })
            }
        }
        val reason = try { lost.await() } finally { monitors.forEach { it.cancel() } }
        Outcome(true, "$reason (aktif ${(SystemClock.elapsedRealtime() - upAt) / 1000} dtk)")
    }

    // ------------------------------------------------------------------ metode: SlowDNS

    /**
     * Coba tiap rute DNS berurutan: jalankan dnstt-client, lalu [tryRoute] memverifikasi rute itu
     * (login SSH, atau uji tunnel untuk V2Ray). null = berhasil, selain itu pesan error.
     */
    private suspend fun runDnsRoutes(p: Profile, s: Settings, tryRoute: suspend (Route, Proc, Boolean) -> String?): Linked {
        val routes = Routes.plan(this, p, store.lastRoute(p.id))
        if (routes.isEmpty()) return Linked(null, "Tidak ada rute: isi minimal satu resolver (UDP/DoT/DoH) atau pilih tipe yang didukung")
        if (!Natives.dnstt(this).exists()) return Linked(null, "Binary dnstt tidak ada untuk ABI perangkat ini")

        var lastErr = "tidak ada rute yang berhasil"
        for ((i, r) in routes.withIndex()) {
            if (userStop) return Linked(null, "dibatalkan")
            TunnelBus.set(Phase.StartingDns, "Rute ${i + 1}/${routes.size}: ${r.label}", r.label)
            TunnelBus.i("Mencoba rute ${r.label}")
            val cmd = Routes.command(this, p, r, s.localPort)
            TunnelBus.i("dnstt: " + cmd.drop(1).joinToString(" ") { if (it == p.pubKey.trim()) "<pubkey>" else it })
            val d: Proc
            try {
                d = Natives.spawn("dnstt", cmd)
            } catch (e: Exception) {
                lastErr = "gagal menjalankan dnstt: ${e.message}"
                continue
            }
            dnstt = d
            delay(800)
            if (!d.alive()) { lastErr = "dnstt langsung berhenti (kode ${d.exitCode()}), cek log"; continue }

            val err = tryRoute(r, d, routes.size > 1)
            if (err == null) return Linked(r.label, routeKey = r.key)
            lastErr = err
            TunnelBus.w("Rute ${r.label} gagal: $err")
            d.kill(); dnstt = null
            if (err.startsWith("Login SSH ditolak")) break // user/password salah: rute lain tidak akan membantu
        }
        return Linked(null, lastErr)
    }

    /** Revisi dnstt yang dibuild ke APK ini (ditulis workflow ke assets). */
    private fun logDnsttVersion() {
        val v = runCatching { assets.open("dnstt-version.txt").bufferedReader().use { it.readText().trim() } }.getOrNull()
        TunnelBus.i("Binary dnstt: ${v?.ifBlank { null } ?: "revisi tidak tercatat"}")
    }

    private suspend fun connectDnstt(p: Profile, s: Settings): Linked = runDnsRoutes(p, s) { r, d, _ ->
        // Tahap 1: pastikan tunnel DNS benar-benar hidup (tipe record, public key, dan domain NS cocok dengan server)
        // dengan menunggu banner SSH. Rute yang salah gagal cepat dan jelas, bukan menunggu kex SSH sampai habis waktu.
        TunnelBus.set(Phase.ConnectingSsh, "Menguji tunnel DNS lewat ${r.label}", r.label)
        if (!dnsttVersionLogged) { dnsttVersionLogged = true; logDnsttVersion() }
        probeSshBanner(s.localPort, d, 30_000)?.let { return@runDnsRoutes it }
        delay(300)
        // Tahap 2: login SSH. Tunnel sudah terbukti hidup, jadi beri waktu panjang: kex lewat DNS butuh banyak
        // putaran kecil dan di jaringan seluler yang lambat bisa lebih dari 30 detik.
        TunnelBus.set(Phase.ConnectingSsh, "Login SSH lewat ${r.label}", r.label)
        val t = SshTunnel()
        val err = t.open(
            "127.0.0.1", s.localPort, p.sshUser, p.sshPass, s.socksPort, s.sshCompression,
            timeoutMs = 90_000, retryDelayMs = 3000,
            isCancelled = { userStop || !d.alive() },
        )
        if (err == null) ssh = t else t.close()
        err
    }

    /**
     * Tunggu banner SSH ("SSH-2.0-...") lewat port lokal dnstt. Banner dikirim server segera setelah koneksi,
     * jadi kedatangannya membuktikan seluruh jalur (resolver, tipe record, Noise key, domain NS, sshd) bekerja.
     * @return null kalau banner diterima, atau pesan error yang menjelaskan apa yang kemungkinan salah.
     */
    private fun probeSshBanner(port: Int, d: Proc, timeoutMs: Long): String? {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        try {
            Socket().use { sk ->
                sk.connect(InetSocketAddress("127.0.0.1", port), 3000)
                sk.soTimeout = 1000
                val inp = sk.getInputStream()
                val sb = StringBuilder()
                var lines = 0
                while (System.currentTimeMillis() < deadline) {
                    if (userStop) return "dibatalkan"
                    if (!d.alive()) return "dnstt berhenti (kode ${d.exitCode()})"
                    try {
                        val c = inp.read()
                        if (c < 0) return "tunnel menutup koneksi sebelum mengirim banner SSH (cek SSH server dan port tujuan dnstt-server)"
                        if (c == '\n'.code) {
                            val line = sb.toString().trim(); sb.setLength(0)
                            if (line.startsWith("SSH-")) {
                                TunnelBus.i("Tunnel DNS hidup: banner SSH diterima dalam ${(System.currentTimeMillis() - start) / 1000.0} dtk")
                                return null
                            }
                            if (++lines > 20) return "data dari server bukan banner SSH"
                        } else if (sb.length < 300) sb.append(c.toChar())
                    } catch (_: SocketTimeoutException) { /* tunggu lagi */ }
                }
            }
        } catch (e: IOException) {
            return if (userStop) "dibatalkan" else "uji tunnel gagal: ${e.message}"
        }
        return "tidak ada banner SSH lewat tunnel DNS dalam ${timeoutMs / 1000} detik: tipe record, public key, atau domain NS tidak cocok dengan server, atau resolver memblokir tipe ini"
    }

    /**
     * Uji tunnel DNS tanpa SSH: kirim permintaan HTTP biasa lewat port lokal dnstt. Server upstream
     * (Xray/V2Ray) hampir selalu membalas atau menutup koneksi; tidak ada reaksi sama sekali = tunnel mati.
     */
    private fun probeTunnel(port: Int, d: Proc, timeoutMs: Long): String? {
        val start = System.currentTimeMillis()
        val deadline = start + timeoutMs
        try {
            Socket().use { sk ->
                sk.connect(InetSocketAddress("127.0.0.1", port), 2000)
                sk.soTimeout = 1000
                sk.getOutputStream().apply { write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                val inp = sk.getInputStream()
                while (System.currentTimeMillis() < deadline) {
                    if (userStop) return "dibatalkan"
                    if (!d.alive()) return "dnstt berhenti (kode ${d.exitCode()})"
                    try {
                        if (inp.read() >= 0) return null
                        // koneksi ditutup: wajar kalau sudah melewati handshake tunnel, mencurigakan kalau instan
                        return if (System.currentTimeMillis() - start >= 1200) null else "tunnel menutup koneksi terlalu cepat"
                    } catch (_: SocketTimeoutException) { /* tunggu lagi */ }
                }
            }
        } catch (e: IOException) {
            return "uji tunnel gagal: ${e.message}"
        }
        return "tidak ada balasan server lewat tunnel DNS dalam ${timeoutMs / 1000} detik"
    }

    // ------------------------------------------------------------------ metode: SSH + WebSocket / HTTP payload

    private suspend fun connectPayload(p: Profile, s: Settings): Linked {
        val ws = p.method == Method.SSH_WS
        val label = buildString {
            append(if (ws) "SSH · WebSocket" else "SSH · Payload")
            if (p.useTls) append(" · TLS")
        }
        val viaProxy = !ws && p.proxyHost.isNotBlank()
        val connectHost = if (viaProxy) p.proxyHost.trim() else p.sshHost.trim()
        val connectPort = if (viaProxy) p.proxyPort else p.sshPort

        TunnelBus.set(Phase.StartingDns, "Membuka jalur ke $connectHost:$connectPort", label)
        TunnelBus.i("Mencoba $label → $connectHost:$connectPort" + if (viaProxy) " (HTTP proxy, target ${p.sshHost}:${p.sshPort})" else "")
        val pt = PayloadTunnel(
            connectHost = connectHost, connectPort = connectPort,
            targetHost = p.sshHost.trim(), targetPort = p.sshPort,
            tls = p.useTls, sni = p.sni.trim(),
            payloadTemplate = if (ws) "" else p.payload,
            websocketDefault = ws, wsHost = p.wsHost.trim(), wsPath = p.wsPath.trim(),
        )
        pt.start(s.localPort)?.let { return Linked(null, it) }
        payload = pt

        TunnelBus.set(Phase.ConnectingSsh, "Login SSH lewat $label", label)
        val t = SshTunnel()
        val err = t.open(
            "127.0.0.1", s.localPort, p.sshUser, p.sshPass, s.socksPort, s.sshCompression,
            timeoutMs = 45_000, isCancelled = { userStop }, retryDelayMs = 3000, // jangan banjiri server dengan koneksi payload baru
        )
        if (err == null) { ssh = t; return Linked(label) }
        t.close(); pt.close(); payload = null
        return Linked(null, err)
    }

    // ------------------------------------------------------------------ metode: V2Ray (Xray-core)

    private suspend fun connectV2ray(p: Profile, s: Settings): Linked {
        val link = p.v2 ?: return Linked(null, "Link V2Ray tidak valid (dukung vmess://, vless://, trojan://)")
        val bin = Natives.xray(this)
        if (!bin.exists()) return Linked(null, "Binary Xray tidak ada di APK ini (build CI untuk Xray gagal atau ABI tidak didukung)")
        val viaDns = p.v2ViaDns
        var dnsRouteKey: String? = null
        var label = "V2Ray · ${link.proto.uppercase()}/${link.network}" + if (link.security != "none") "+${link.security}" else ""

        if (viaDns) {
            // Tunnel DNS dulu: dnstt-client membuka 127.0.0.1:localPort -> server dnstt -> Xray/V2Ray di server
            val dl = runDnsRoutes(p, s) { r, d, multi ->
                if (!multi) null else {
                    TunnelBus.set(Phase.ConnectingSsh, "Menguji tunnel DNS lewat ${r.label}", r.label)
                    probeTunnel(s.localPort, d, 15_000)
                }
            }
            val dnsLabel = dl.label ?: return dl
            dnsRouteKey = dl.routeKey
            label = "V2Ray · SlowDNS · $dnsLabel"
        }

        val xrayPhase = if (viaDns) Phase.ConnectingSsh else Phase.StartingDns
        TunnelBus.set(xrayPhase, "Menjalankan core Xray", label)
        val cfg = File(noBackupFilesDir, "xray.json")
        cfg.writeText(V2Ray.config(link, s.socksPort, listOf(s.dns1, s.dns2), if (viaDns) s.localPort else null))
        TunnelBus.i("Xray: ${V2Ray.describe(link)}")
        val x = try {
            Natives.spawn("xray", listOf(bin.absolutePath, "run", "-c", cfg.absolutePath))
        } catch (e: Exception) { return Linked(null, "gagal menjalankan xray: ${e.message}") }
        xray = x

        TunnelBus.set(Phase.ConnectingSsh, "Menunggu SOCKS5 lokal siap", label)
        val deadline = System.currentTimeMillis() + 12_000
        while (System.currentTimeMillis() < deadline) {
            if (userStop) return Linked(null, "dibatalkan")
            if (!x.alive()) return Linked(null, "xray berhenti (kode ${x.exitCode()}), lihat baris 'xray:' di log")
            // Sapa SOCKS5 (05 01 00) supaya Xray tidak mencatat koneksi kosong sebagai "malformed HTTP request"
            val ok = runCatching {
                Socket().use { sk ->
                    sk.soTimeout = 600
                    sk.connect(InetSocketAddress("127.0.0.1", s.socksPort), 300)
                    sk.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
                    val r = ByteArray(2)
                    sk.getInputStream().read(r) == 2 && r[0] == 5.toByte()
                }
            }.getOrDefault(false)
            if (ok) return Linked(label, routeKey = dnsRouteKey)
            delay(250)
        }
        return Linked(null, "xray tidak membuka SOCKS5 di port ${s.socksPort} dalam 12 detik")
    }

    @Volatile private var lastVpnError: String? = null
    @Volatile private var dnsttVersionLogged = false

    /** establish() bisa null sesaat setelah antarmuka lama ditutup; coba beberapa kali dan catat alasan sebenarnya. */
    private suspend fun establishVpnWithRetry(s: Settings): ParcelFileDescriptor? {
        for (i in 1..4) {
            if (userStop) return null
            establishVpn(s)?.let { return it }
            TunnelBus.w("VPN: antarmuka belum bisa dibuat (percobaan $i/4)" + (lastVpnError?.let { ": $it" } ?: ""))
            if (VpnService.prepare(this) != null) return null // izin memang belum ada, tidak perlu mengulang
            delay(500L * i)
        }
        return null
    }

    private fun establishVpn(s: Settings): ParcelFileDescriptor? {
        val b = Builder().setSession("KH VPN").setMtu(s.mtu)
            .addAddress(Tun2Socks.CLIENT_V4, 24)
            .addRoute("0.0.0.0", 0)
        listOf(s.dns1, s.dns2).filter { it.isNotBlank() }.forEach { runCatching { b.addDnsServer(it.trim()) } }
        // Blokir kebocoran IPv6: rute ::/0 masuk ke TUN, tun2socks membuangnya sehingga app fallback ke IPv4
        runCatching { b.addAddress("fdfe:dcba:9876::1", 126); b.addRoute("::", 0) }
        // Proses anak (dnstt) memakai UID yang sama, jadi dikecualikan dari VPN: tidak ada loop
        runCatching { b.addDisallowedApplication(packageName) }
        s.bypassApps.forEach { pkg -> runCatching { b.addDisallowedApplication(pkg) } }
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        lastVpnError = null
        return try {
            b.establish()
        } catch (e: Exception) {
            lastVpnError = e.message ?: e.javaClass.simpleName
            null
        }
    }

    // ------------------------------------------------------------------ pembersihan

    private fun teardown() {
        runCatching { ssh?.close() }; ssh = null
        runCatching { payload?.close() }; payload = null
        runCatching { xray?.kill() }; xray = null
        runCatching { File(noBackupFilesDir, "xray.json").delete() }
        runCatching { tun?.kill() }; tun = null
        runCatching { dnstt?.kill() }; dnstt = null
        runCatching { vpn?.close() }; vpn = null
    }

    private fun runBlockingTeardown() { teardown(); releaseLocks() }

    /**
     * Tahan CPU dan Wi-Fi tetap aktif selama tunnel berjalan. Tanpa ini, saat layar mati Android menidurkan CPU,
     * keepalive SSH telat, server/CDN menutup koneksi, lalu aplikasi putus-sambung terus.
     */
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        runCatching {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "khvpn:tunnel").apply { setReferenceCounted(false); acquire() }
            }
        }
        runCatching {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                wifiLock = wm.createWifiLock(mode, "khvpn:wifi").apply { setReferenceCounted(false); acquire() }
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }; wifiLock = null
    }

    private fun fail(reason: String) {
        TunnelBus.e(reason)
        teardown()
        TunnelBus.set(Phase.Failed, reason)
        stopForegroundCompat()
        stopSelf()
    }

    private fun shutdown() {
        TunnelBus.set(Phase.Stopping, "Memutuskan…")
        runJob?.cancel()
        teardown()
        TunnelBus.i("Diputuskan")
        TunnelBus.set(Phase.Idle)
        stopForegroundCompat()
        stopSelf()
    }

    // ------------------------------------------------------------------ notifikasi

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    /** Kecepatan = lalu lintas UID aplikasi ini (dnstt/xray/ssh jalan di UID yang sama), yaitu data nyata di jaringan. */
    private suspend fun sampleSpeed(nm: NotificationManager) {
        val uid = android.os.Process.myUid()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        var lastRx = TrafficStats.getUidRxBytes(uid)
        var lastTx = TrafficStats.getUidTxBytes(uid)
        if (lastRx < 0 || lastTx < 0) return // perangkat tidak mendukung
        val baseRx = lastRx
        val baseTx = lastTx
        var lastT = SystemClock.elapsedRealtime()
        while (true) {
            delay(if (pm.isInteractive) 1000L else 5000L) // layar mati: jarang bangun, hemat baterai
            val now = SystemClock.elapsedRealtime()
            val rx = TrafficStats.getUidRxBytes(uid)
            val tx = TrafficStats.getUidTxBytes(uid)
            if (rx < 0 || tx < 0) return
            if (!pm.isInteractive) { lastRx = rx; lastTx = tx; lastT = now; continue }
            val dt = (now - lastT).coerceAtLeast(1L) / 1000.0
            val sp = Speed(
                down = ((rx - lastRx) / dt).toLong().coerceAtLeast(0), up = ((tx - lastTx) / dt).toLong().coerceAtLeast(0),
                totalDown = (rx - baseRx).coerceAtLeast(0), totalUp = (tx - baseTx).coerceAtLeast(0),
            )
            lastRx = rx; lastTx = tx; lastT = now
            TunnelBus.setSpeed(sp)
            if (store.settings.value.showSpeed) nm.notify(NOTIF_ID, buildNotification(TunnelBus.state.value, sp))
        }
    }

    private fun buildNotification(st: TunnelUi, sp: Speed = Speed()): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = Intent(this, KhVpnService::class.java).setAction(ACTION_STOP)
        val stop = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(this, 1, stopIntent, flags)
        else PendingIntent.getService(this, 1, stopIntent, flags)
        val connected = st.phase == Phase.Connected
        val showSpeed = connected && store.settings.value.showSpeed
        val text = when (st.phase) {
            Phase.Connected -> if (showSpeed) "↓ ${fmtRate(sp.down)}   ↑ ${fmtRate(sp.up)}" else "Terhubung · ${st.route}"
            Phase.Failed -> st.detail.ifBlank { "Gagal" }
            else -> st.detail.ifBlank { "Menyiapkan…" }
        }
        return NotificationCompat.Builder(this, KhApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("KH VPN")
            .setContentText(text)
            .setSubText(if (connected) st.route else null)
            .apply {
                if (connected) {
                    setShowWhen(true).setWhen(st.connectedAt).setUsesChronometer(true) // timer digambar sistem, tanpa update
                }
                if (showSpeed) setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Lewat ${st.route}\n↓ ${fmtRate(sp.down)}   ↑ ${fmtRate(sp.up)}\nTotal ↓ ${fmtBytes(sp.totalDown)}   ↑ ${fmtBytes(sp.totalUp)}",
                    ),
                )
            }
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notif_disconnect), stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.khvpn.app.START"
        const val ACTION_STOP = "com.khvpn.app.STOP"
        const val EXTRA_PROFILE = "profile"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, profileId: String?) {
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, KhVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_PROFILE, profileId),
            )
        }

        fun stop(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, KhVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
