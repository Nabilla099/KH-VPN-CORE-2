package com.khvpn.app.vpn

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Hasil parse link berbagi vmess:// vless:// trojan://. */
data class V2Link(
    val proto: String,          // vmess | vless | trojan
    val name: String,
    val host: String,
    val port: Int,
    val id: String,             // uuid (vmess/vless) atau password (trojan)
    val aid: Int = 0,
    val vmessSecurity: String = "auto",
    val flow: String = "",
    val network: String = "tcp",      // tcp | ws | grpc | httpupgrade
    val security: String = "none",    // none | tls | reality
    val sni: String = "",
    val hostHeader: String = "",
    val path: String = "",
    val serviceName: String = "",
    val fingerprint: String = "",
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    val spiderX: String = "",
    val allowInsecure: Boolean = false,
)

object V2Ray {
    private val NETS = setOf("tcp", "ws", "grpc", "httpupgrade")

    fun parse(raw: String): V2Link? = runCatching {
        val link = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        when {
            link.startsWith("vmess://", true) -> parseVmess(link.substring(8))
            link.startsWith("vless://", true) -> parseUrl("vless", link)
            link.startsWith("trojan://", true) -> parseUrl("trojan", link)
            else -> null
        }
    }.getOrNull()

    private fun b64(s: String): String {
        var t = s.trim().replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun parseVmess(body: String): V2Link? {
        val o = JSONObject(b64(body.substringBefore("#")))
        val host = o.optString("add"); val port = o.optString("port").toIntOrNull() ?: return null
        val id = o.optString("id"); if (host.isBlank() || id.isBlank()) return null
        val net = o.optString("net", "tcp").lowercase().let { if (it in NETS) it else "tcp" }
        val tls = o.optString("tls").lowercase()
        return V2Link(
            proto = "vmess", name = o.optString("ps"), host = host, port = port, id = id,
            aid = o.optString("aid").toIntOrNull() ?: 0, vmessSecurity = o.optString("scy").ifBlank { "auto" },
            network = net, security = if (tls == "tls") "tls" else "none",
            sni = o.optString("sni"), hostHeader = o.optString("host"), path = o.optString("path"),
            serviceName = if (net == "grpc") o.optString("path") else "",
            fingerprint = o.optString("fp"), alpn = o.optString("alpn"),
        )
    }

    private fun parseUrl(proto: String, link: String): V2Link? {
        val u = Uri.parse(link)
        val host = u.host ?: return null
        val port = if (u.port > 0) u.port else return null
        val id = Uri.decode(u.userInfo ?: return null)
        if (id.isBlank()) return null
        fun q(k: String) = u.getQueryParameter(k) ?: ""
        val net = q("type").lowercase().ifBlank { "tcp" }.let { if (it in NETS) it else "tcp" }
        val sec = q("security").lowercase().ifBlank { if (proto == "trojan") "tls" else "none" }
        return V2Link(
            proto = proto, name = Uri.decode(u.fragment ?: ""), host = host, port = port, id = id,
            flow = q("flow"), network = net, security = if (sec in setOf("tls", "reality")) sec else "none",
            sni = q("sni").ifBlank { q("peer") }, hostHeader = q("host"), path = q("path"),
            serviceName = q("serviceName"), fingerprint = q("fp"), alpn = q("alpn"),
            publicKey = q("pbk"), shortId = q("sid"), spiderX = q("spx"),
            allowInsecure = q("allowInsecure") == "1" || q("insecure") == "1",
        )
    }

    /** Susun ulang link berbagi dari V2Link (dipakai editor V2Ray). */
    fun toLink(l: V2Link): String {
        if (l.proto == "vmess") {
            val o = JSONObject()
                .put("v", "2").put("ps", l.name).put("add", l.host).put("port", l.port.toString()).put("id", l.id)
                .put("aid", l.aid.toString()).put("scy", l.vmessSecurity).put("net", l.network).put("type", "none")
                .put("host", l.hostHeader).put("path", if (l.network == "grpc") l.serviceName else l.path)
                .put("tls", if (l.security == "tls") "tls" else "").put("sni", l.sni).put("alpn", l.alpn).put("fp", l.fingerprint)
            return "vmess://" + Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
        val q = linkedMapOf<String, String>()
        q["type"] = l.network
        q["security"] = l.security
        if (l.flow.isNotBlank()) q["flow"] = l.flow
        if (l.sni.isNotBlank()) q["sni"] = l.sni
        if (l.hostHeader.isNotBlank()) q["host"] = l.hostHeader
        if (l.path.isNotBlank()) q["path"] = l.path
        if (l.serviceName.isNotBlank()) q["serviceName"] = l.serviceName
        if (l.fingerprint.isNotBlank()) q["fp"] = l.fingerprint
        if (l.alpn.isNotBlank()) q["alpn"] = l.alpn
        if (l.publicKey.isNotBlank()) q["pbk"] = l.publicKey
        if (l.shortId.isNotBlank()) q["sid"] = l.shortId
        if (l.spiderX.isNotBlank()) q["spx"] = l.spiderX
        if (l.allowInsecure) q["allowInsecure"] = "1"
        val host = if (l.host.contains(':') && !l.host.startsWith("[")) "[${l.host}]" else l.host
        val query = q.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        return "${l.proto}://${Uri.encode(l.id)}@$host:${l.port}?$query" + if (l.name.isNotBlank()) "#" + Uri.encode(l.name) else ""
    }

    fun describe(l: V2Link) = buildString {
        append(l.proto.uppercase()).append(" · ").append(l.network)
        if (l.security != "none") append(" · ").append(l.security)
        append(" · ").append(l.host).append(':').append(l.port)
    }

    /** Konfigurasi Xray: satu inbound SOCKS5 (TCP+UDP) di loopback, satu outbound sesuai link. */
    fun config(l: V2Link, socksPort: Int, dns: List<String>, viaLocalPort: Int? = null): String {
        // viaLocalPort != null: server dijangkau lewat tunnel SlowDNS (dnstt-client mendengarkan di 127.0.0.1:port);
        // host asli tetap dipakai untuk SNI / header Host.
        val addr = if (viaLocalPort != null) "127.0.0.1" else l.host
        val outPort = viaLocalPort ?: l.port
        val isDomain = viaLocalPort == null && !Regex("^[0-9.]+$").matches(l.host) && !l.host.contains(':')
        val user = JSONObject()
        val settings = JSONObject()
        when (l.proto) {
            "vless" -> {
                user.put("id", l.id).put("encryption", "none")
                if (l.flow.isNotBlank()) user.put("flow", l.flow)
                settings.put("vnext", JSONArray().put(JSONObject().put("address", addr).put("port", outPort).put("users", JSONArray().put(user))))
            }
            "vmess" -> {
                user.put("id", l.id).put("alterId", l.aid).put("security", l.vmessSecurity)
                settings.put("vnext", JSONArray().put(JSONObject().put("address", addr).put("port", outPort).put("users", JSONArray().put(user))))
            }
            else -> settings.put("servers", JSONArray().put(JSONObject().put("address", addr).put("port", outPort).put("password", l.id)))
        }

        val stream = JSONObject().put("network", l.network).put("security", l.security)
        val serverName = l.sni.ifBlank { l.hostHeader.ifBlank { l.host } }
        when (l.security) {
            "tls" -> stream.put("tlsSettings", JSONObject().put("serverName", serverName).put("allowInsecure", l.allowInsecure).apply {
                put("fingerprint", l.fingerprint.ifBlank { "chrome" })
                if (l.alpn.isNotBlank()) put("alpn", JSONArray(l.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            })
            "reality" -> stream.put("realitySettings", JSONObject().put("serverName", serverName)
                .put("fingerprint", l.fingerprint.ifBlank { "chrome" }).put("publicKey", l.publicKey)
                .put("shortId", l.shortId).put("spiderX", l.spiderX.ifBlank { "/" }))
        }
        when (l.network) {
            "ws" -> stream.put("wsSettings", JSONObject().put("path", l.path.ifBlank { "/" })
                .put("headers", JSONObject().put("Host", l.hostHeader.ifBlank { serverName })))
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", l.serviceName))
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().put("path", l.path.ifBlank { "/" }).put("host", l.hostHeader.ifBlank { serverName }))
        }
        // Go statis di Android tidak punya resolver sistem: pakai DNS internal Xray untuk host server
        if (isDomain) stream.put("sockopt", JSONObject().put("domainStrategy", "UseIP"))

        val outbound = JSONObject().put("tag", "proxy").put("protocol", l.proto).put("settings", settings).put("streamSettings", stream)
        val inbound = JSONObject().put("tag", "socks").put("listen", "127.0.0.1").put("port", socksPort).put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")))

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning").put("access", "none"))
            // buffer per koneksi kecil: hemat RAM di HP murah
            .put("policy", JSONObject().put("levels", JSONObject().put("0", JSONObject().put("bufferSize", 64).put("connIdle", 180))))
            .put("dns", JSONObject().put("servers", JSONArray(dns.filter { it.isNotBlank() }.ifEmpty { listOf("8.8.8.8") })))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .toString()
    }
}
