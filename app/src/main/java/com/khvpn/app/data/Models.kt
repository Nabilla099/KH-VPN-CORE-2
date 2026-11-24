package com.khvpn.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tipe record DNS yang dipakai tunnel dnstt.
 * AUTO = TXT lalu NULL; ALL = semua tipe berurutan. Tipe selain TXT/NULL bersifat eksperimental:
 * server harus memakai dnstt-server hasil patch CI dengan -type yang sama, dan resolver publik
 * bisa saja memfilter tipe tertentu.
 */
enum class RecordType(val label: String, val flag: String?) {
    AUTO("Otomatis (TXT, NULL)", null),
    ALL("Semua tipe (urut)", null),
    TXT("TXT", "TXT"),
    NULL("NULL", "NULL"),
    SPF("SPF", "SPF"),
    KEY("KEY", "KEY"),
    OPENPGPKEY("OPENPGPKEY", "OPENPGPKEY"),
    URI("URI", "URI"),
    CUSTOM("Kustom (isi sendiri)", null);

    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: AUTO }
}

/** Cara tersambung ke server. */
enum class Method(val label: String, val short: String) {
    DNSTT("SlowDNS (dnstt + SSH)", "SlowDNS"),
    SSH_WS("SSH + WebSocket", "SSH WS"),
    HTTP_PAYLOAD("SSH + HTTP Payload", "Payload"),
    V2RAY("V2Ray / Xray", "V2Ray");

    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: DNSTT }
}

/** Jalur ke resolver. */
enum class Transport(val label: String) {
    AUTO("Otomatis"), UDP("UDP :53"), DOT("DoT :853"), DOH("DoH https");

    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: AUTO }
}

enum class ThemeMode(val label: String) {
    SYSTEM("Sistem"), LIGHT("Terang"), DARK("Gelap");

    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: SYSTEM }
}

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val method: Method = Method.DNSTT,
    val nsDomain: String = "",        // domain NS yang mengarah ke dnstt-server
    val pubKey: String = "",          // public key server (hex)
    val resolverUdp: String = "8.8.8.8",
    val resolverDot: String = "",     // contoh: 1.1.1.1:853
    val resolverDoh: String = "",     // contoh: https://1.1.1.1/dns-query
    val sshUser: String = "",
    val sshPass: String = "",
    val recordType: RecordType = RecordType.AUTO,
    val customType: String = "",     // dipakai bila recordType = CUSTOM: nama (mis. NULL) atau angka tipe RR (mis. 65400)
    val transport: Transport = Transport.AUTO,
    val useUdpgw: Boolean = true,
    val udpgw: String = "127.0.0.1:7300",
    // SSH + WebSocket / HTTP payload
    val sshHost: String = "",         // host/IP server (atau host CDN)
    val sshPort: Int = 80,
    val useTls: Boolean = false,
    val sni: String = "",
    val wsHost: String = "",          // header Host untuk WebSocket
    val wsPath: String = "/",
    val payload: String = "",
    val proxyHost: String = "",       // opsional: HTTP proxy untuk payload
    val proxyPort: Int = 0,
    // V2Ray
    val v2rayLink: String = "",
    val v2ViaDns: Boolean = false,      // V2Ray lewat tunnel SlowDNS (dnstt)
) {
    val nsDomainLooksLikeIp get() = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?$").matches(nsDomain.trim())

    val v2: com.khvpn.app.vpn.V2Link? get() = if (method == Method.V2RAY) com.khvpn.app.vpn.V2Ray.parse(v2rayLink) else null

    val isValid get() = when (method) {
        Method.DNSTT -> nsDomain.isNotBlank() && !nsDomainLooksLikeIp && pubKey.isNotBlank() && sshUser.isNotBlank() && typeOk
        Method.SSH_WS -> sshHost.isNotBlank() && sshPort in 1..65535 && sshUser.isNotBlank()
        Method.HTTP_PAYLOAD -> sshHost.isNotBlank() && sshPort in 1..65535 && payload.isNotBlank() && sshUser.isNotBlank() &&
            (proxyHost.isBlank() || proxyPort in 1..65535)
        Method.V2RAY -> v2 != null && (!v2ViaDns || dnsOk)
    }

    private val dnsOk get() = nsDomain.isNotBlank() && !nsDomainLooksLikeIp && pubKey.isNotBlank() && typeOk

    /** Tipe kustom harus berupa nama huruf/angka pendek atau angka 1..65535. */
    val customTypeValid get() = customType.trim().let { t ->
        t.matches(Regex("[A-Za-z][A-Za-z0-9]{0,15}")) || (t.matches(Regex("[0-9]{1,5}")) && t.toInt() in 1..65535)
    }
    private val typeOk get() = recordType != RecordType.CUSTOM || customTypeValid

    /** Syarat minimum untuk menyimpan (user SSH boleh menyusul). */
    val canSave get() = when (method) {
        Method.DNSTT -> nsDomain.isNotBlank() && !nsDomainLooksLikeIp && pubKey.isNotBlank()
        Method.SSH_WS -> sshHost.isNotBlank() && sshPort in 1..65535
        Method.HTTP_PAYLOAD -> sshHost.isNotBlank() && sshPort in 1..65535 && payload.isNotBlank()
        Method.V2RAY -> v2rayLink.isNotBlank() && (!v2ViaDns || dnsOk)
    }

    /** Teks singkat tujuan untuk kartu/daftar. */
    val endpoint get() = when (method) {
        Method.DNSTT -> nsDomain.ifBlank { "domain belum diisi" }
        Method.SSH_WS, Method.HTTP_PAYLOAD -> if (sshHost.isBlank()) "host belum diisi" else "$sshHost:$sshPort"
        Method.V2RAY -> v2?.let { "${it.host}:${it.port}" } ?: "link belum valid"
    }

    val summary get() = when (method) {
        Method.DNSTT -> "${if (recordType == RecordType.CUSTOM) customType.trim().uppercase().ifBlank { "Kustom" } else recordType.label.substringBefore(" (")} · ${transport.label}"
        Method.SSH_WS -> "SSH WS" + if (useTls) " · TLS" else ""
        Method.HTTP_PAYLOAD -> buildString { append("Payload"); if (proxyHost.isNotBlank()) append(" · proxy"); if (useTls) append(" · TLS") }
        Method.V2RAY -> v2?.let { "${it.proto.uppercase()} · ${it.network}" + (if (it.security != "none") " · ${it.security}" else "") + (if (v2ViaDns) " · SlowDNS" else "") } ?: "V2Ray"
    }

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("method", method.name); put("ns", nsDomain); put("key", pubKey)
        put("udp", resolverUdp); put("dot", resolverDot); put("doh", resolverDoh)
        put("user", sshUser); put("pass", sshPass)
        put("type", recordType.name); put("ctype", customType); put("transport", transport.name)
        put("useUdpgw", useUdpgw); put("udpgw", udpgw)
        put("sshHost", sshHost); put("sshPort", sshPort); put("tls", useTls); put("sni", sni)
        put("wsHost", wsHost); put("wsPath", wsPath); put("payload", payload)
        put("proxyHost", proxyHost); put("proxyPort", proxyPort); put("v2", v2rayLink); put("v2dns", v2ViaDns)
    }

    companion object {
        fun fromJson(o: JSONObject) = Profile(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name"), method = Method.of(o.optString("method")),
            nsDomain = o.optString("ns"), pubKey = o.optString("key"),
            resolverUdp = o.optString("udp", "8.8.8.8"), resolverDot = o.optString("dot"),
            resolverDoh = o.optString("doh"), sshUser = o.optString("user"), sshPass = o.optString("pass"),
            recordType = RecordType.of(o.optString("type")), customType = o.optString("ctype"), transport = Transport.of(o.optString("transport")),
            useUdpgw = o.optBoolean("useUdpgw", true), udpgw = o.optString("udpgw", "127.0.0.1:7300"),
            sshHost = o.optString("sshHost"), sshPort = o.optInt("sshPort", 80), useTls = o.optBoolean("tls", false),
            sni = o.optString("sni"), wsHost = o.optString("wsHost"), wsPath = o.optString("wsPath", "/"),
            payload = o.optString("payload"), proxyHost = o.optString("proxyHost"), proxyPort = o.optInt("proxyPort", 0),
            v2rayLink = o.optString("v2"), v2ViaDns = o.optBoolean("v2dns", false),
        )

        fun listToJson(l: List<Profile>) = JSONArray().also { a -> l.forEach { a.put(it.toJson()) } }.toString()
        fun listFromJson(s: String?): List<Profile> = runCatching {
            val a = JSONArray(s ?: "[]"); (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }
}

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val showSpeed: Boolean = true,    // kecepatan unduh/unggah di notifikasi
    val dns1: String = "8.8.8.8",
    val dns2: String = "8.8.4.4",
    val socksPort: Int = 1080,
    val localPort: Int = 2222,        // port lokal yang dibuka dnstt-client, tujuan SSH
    val mtu: Int = 1500,
    val sshCompression: Boolean = false,
    val autoReconnect: Boolean = true,
    val bypassApps: Set<String> = emptySet(), // aplikasi yang TIDAK lewat VPN
)
