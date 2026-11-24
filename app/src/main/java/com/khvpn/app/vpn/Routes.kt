package com.khvpn.app.vpn

import android.content.Context
import com.khvpn.app.data.Profile
import com.khvpn.app.data.RecordType
import com.khvpn.app.data.Transport
import java.net.Inet4Address
import java.net.InetAddress

/** Satu kombinasi jalur resolver (+ alamat resolver) + tipe record DNS. */
data class Route(val transport: Transport, val type: RecordType, val addr: String = "", val custom: String = "") {
    /** Nilai untuk flag -type dnstt (null = bawaan TXT tanpa flag). */
    val flag get() = if (type == RecordType.CUSTOM) custom.trim().ifBlank { null } else type.flag
    val key get() = "${transport.name}/${if (type == RecordType.CUSTOM) "CUSTOM:" + custom.trim().uppercase() else type.name}/$addr"
    val label get() = transport.name + (if (addr.isNotBlank()) " ${shortAddr(addr)}" else "") + " · " +
        (if (type == RecordType.CUSTOM) custom.trim().uppercase() else type.label)
}

private fun shortAddr(a: String) = a.removePrefix("https://").removePrefix("http://").substringBefore('/')

object Routes {
    @Volatile private var typeSupported: Boolean? = null
    private const val MAX_ROUTES = 16

    /** Apakah binary dnstt mendukung flag -type (build CI yang dipatch). Binary lama hanya TXT. */
    fun supportsType(ctx: Context): Boolean = typeSupported ?: run {
        val help = Natives.helpText(Natives.dnstt(ctx), "-h")
        (help.contains("-type")).also { typeSupported = it }
    }

    /**
     * Susun urutan rute yang akan dicoba. Mode Otomatis = semua kombinasi yang tersedia.
     * Setiap kolom resolver boleh berisi beberapa alamat (pisahkan dengan koma/spasi): bila satu resolver
     * diblokir atau dibatasi operator, aplikasi otomatis pindah ke resolver berikutnya.
     */
    fun plan(ctx: Context, p: Profile, lastKey: String?): List<Route> {
        val canType = supportsType(ctx)
        val transports = when (p.transport) {
            Transport.AUTO -> listOf(Transport.UDP, Transport.DOT, Transport.DOH)
            else -> listOf(p.transport)
        }.filter { addrsFor(p, it).isNotEmpty() }
        val types = when (p.recordType) {
            RecordType.AUTO -> if (canType) listOf(RecordType.TXT, RecordType.NULL) else listOf(RecordType.TXT)
            RecordType.ALL -> if (canType) RecordType.entries.filter { it.flag != null } else listOf(RecordType.TXT)
            else -> listOf(p.recordType)
        }
        val all = types.flatMap { ty -> transports.flatMap { tr -> addrsFor(p, tr).map { Route(tr, ty, it, p.customType) } } }
        // rute yang terakhir sukses dicoba duluan
        return all.sortedByDescending { it.key == lastKey }.take(MAX_ROUTES)
    }

    private fun list(raw: String) = raw.split(Regex("[,\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun addrsFor(p: Profile, tr: Transport): List<String> = when (tr) {
        Transport.UDP -> list(p.resolverUdp).filter { usable(it) }
        Transport.DOT -> list(p.resolverDot).filter { usable(it) }.filter { a ->
            // Domain NS tunnel bukan resolver DoT: salah isi kolom yang sangat umum
            val bad = hostOf(a).equals(p.nsDomain.trim(), ignoreCase = true)
            if (bad) TunnelBus.w("Kolom DoT berisi domain NS ($a), dilewati. Isi dengan resolver DoT, mis. 1.1.1.1:853")
            !bad
        }.filter { a ->
            val ok = isIpLiteral(hostOf(a))
            if (!ok) TunnelBus.w("DoT $a dilewati: binary dnstt tidak bisa me-resolve nama host. Pakai alamat IP (mis. 1.1.1.1:853), atau pindahkan host ini ke kolom UDP")
            ok
        }
        Transport.DOH -> list(p.resolverDoh).filter { it.startsWith("http", ignoreCase = true) }
        Transport.AUTO -> emptyList()
    }

    /** Host dari "host:port" / "[v6]:port" / "host"; kosong kalau cuma ":53" dsb. */
    private fun hostOf(addr: String): String {
        val a = addr.trim()
        if (a.startsWith("[")) return a.substringAfter("[").substringBefore("]")
        return if (a.count { it == ':' } == 1) a.substringBefore(':') else a
    }
    private fun usable(addr: String) = hostOf(addr).isNotBlank()

    private fun isIpLiteral(h: String) = h.contains(':') || (h.isNotEmpty() && h.all { it.isDigit() || it == '.' })

    private val resolved = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * dnstt adalah binary statis tanpa resolver DNS sistem, jadi nama host resolver UDP diubah ke IP di sini
     * (aplikasi dikecualikan dari VPN, sehingga DNS lewat jaringan asli). Ini yang memungkinkan memakai server
     * SlowDNS langsung sebagai resolver (host:53), seperti pada klien lain.
     */
    private fun resolveUdp(hostPort: String): String {
        val host = hostOf(hostPort)
        if (isIpLiteral(host)) return hostPort
        val port = hostPort.substringAfterLast(':', "53")
        val ip = resolved[host] ?: runCatching {
            val all = InetAddress.getAllByName(host)
            (all.firstOrNull { it is Inet4Address } ?: all.first()).hostAddress
        }.getOrNull()?.also { resolved[host] = it }
        if (ip == null) {
            TunnelBus.w("Resolver $host tidak bisa di-resolve (cek koneksi internet / nama host)")
            return hostPort
        }
        TunnelBus.i("Resolver $host → $ip")
        return if (ip.contains(':')) "[$ip]:$port" else "$ip:$port"
    }

    private fun withPort(addr: String, def: Int): String {
        val a = addr.trim()
        return if (a.substringAfterLast(':', "").all { it.isDigit() } && a.contains(':') && !a.startsWith("http")) a else "$a:$def"
    }

    fun command(ctx: Context, p: Profile, r: Route, localPort: Int): List<String> {
        val c = mutableListOf(Natives.dnstt(ctx).absolutePath)
        when (r.transport) {
            Transport.UDP -> c += listOf("-udp", resolveUdp(withPort(r.addr, 53)))
            Transport.DOT -> c += listOf("-dot", withPort(r.addr, 853))
            Transport.DOH -> c += listOf("-doh", r.addr.trim())
            Transport.AUTO -> error("transport harus konkret")
        }
        c += listOf("-pubkey", p.pubKey.trim())
        if (r.transport == Transport.DOT && withPort(r.addr, 853).endsWith(":53")) {
            TunnelBus.w("DoT memakai port 53; DoT biasanya port 853 (mis. 1.1.1.1:853)")
        }
        val flag = r.flag
        if (flag != null && supportsType(ctx)) c += listOf("-type", flag)
        c += listOf(p.nsDomain.trim(), "127.0.0.1:$localPort")
        return c
    }
}
