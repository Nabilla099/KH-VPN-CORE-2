package com.khvpn.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khvpn.app.data.Method
import com.khvpn.app.data.Profile
import com.khvpn.app.data.RecordType
import com.khvpn.app.data.Store
import com.khvpn.app.data.Transport
import com.khvpn.app.ui.ScreenFrame
import com.khvpn.app.ui.theme.MonoStyle
import com.khvpn.app.vpn.V2Link
import com.khvpn.app.vpn.V2Ray

@Composable
fun ProfilesScreen(store: Store) {
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val selectedId by store.selectedId.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Profile?>(null) }
    var deleting by remember { mutableStateOf<Profile?>(null) }

    ScreenFrame(
        title = "Profil",
        fab = { ExtendedFloatingActionButton(onClick = { editing = Profile() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Profil baru") }) },
    ) {
        if (profiles.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Belum ada profil", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Satu profil = satu cara koneksi: SlowDNS, SSH + WebSocket, SSH + HTTP payload, atau V2Ray.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(profiles, key = { it.id }) { p ->
                    val sel = (selectedId ?: profiles.first().id) == p.id
                    Surface(
                        onClick = { store.select(p.id) }, shape = RoundedCornerShape(20.dp), modifier = Modifier.animateContentSize(),
                        color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = sel, onClick = { store.select(p.id) })
                            Column(Modifier.weight(1f)) {
                                Text(p.name.ifBlank { "Tanpa nama" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.endpoint, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${p.method.short} · ${p.summary}" + if (!p.isValid) " · belum lengkap" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (p.isValid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, "Ubah") }
                            IconButton(onClick = { deleting = p }) { Icon(Icons.Default.Delete, "Hapus") }
                        }
                    }
                }
            }
        }
    }

    editing?.let { p -> ProfileEditor(p, onDismiss = { editing = null }, onSave = { store.save(it); editing = null }) }

    deleting?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Hapus profil?") },
            text = { Text("“${p.name.ifBlank { p.endpoint }}” akan dihapus dari perangkat ini.") },
            confirmButton = { TextButton(onClick = { store.delete(p.id); deleting = null }) { Text("Hapus") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(initial: Profile, onDismiss: () -> Unit, onSave: (Profile) -> Unit) {
    var p by remember { mutableStateOf(initial) }
    var showPass by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (initial.name.isBlank() && initial.endpoint.startsWith("domain belum")) "Profil baru" else "Ubah profil", style = MaterialTheme.typography.titleLarge)

            Dropdown("Metode koneksi", p.method.label, Method.entries.map { it.label to it }) { m ->
                p = p.copy(method = m, sshPort = if (p.sshPort == 80 && m == Method.HTTP_PAYLOAD) 22 else p.sshPort)
            }
            Field("Nama", p.name) { p = p.copy(name = it) }

            val m = p.method
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (m) {
            Method.DNSTT -> {
                NsFields(p) { p = it }
                SshAccount(p, showPass, { showPass = !showPass }) { p = it }
                ResolverFields(p) { p = it }
            }

            Method.SSH_WS -> {
                Field("Host / IP server", p.sshHost, "contoh: sg1.example.com") { p = p.copy(sshHost = it.trim()) }
                PortField("Port server (80 / 443)", p.sshPort) { p = p.copy(sshPort = it) }
                TlsFields(p) { p = it }
                Field("Host header (opsional)", p.wsHost, "kosong = host server") { p = p.copy(wsHost = it.trim()) }
                Field("Path WebSocket", p.wsPath, "/") { p = p.copy(wsPath = it.trim()) }
                SshAccount(p, showPass, { showPass = !showPass }) { p = it }
                Hint("Setelah “101 Switching Protocols”, aliran diteruskan mentah ke sshd (tipe WS-SSH yang umum). Server dengan framing WebSocket penuh belum didukung.")
            }

            Method.HTTP_PAYLOAD -> {
                Field("Host / IP server SSH", p.sshHost, "contoh: 203.0.113.10") { p = p.copy(sshHost = it.trim()) }
                PortField("Port SSH / server", p.sshPort) { p = p.copy(sshPort = it) }
                Field("HTTP proxy (host, opsional)", p.proxyHost, "kosong = langsung ke server") { p = p.copy(proxyHost = it.trim()) }
                if (p.proxyHost.isNotBlank()) PortField("Port proxy", p.proxyPort) { p = p.copy(proxyPort = it) }
                TlsFields(p) { p = it }
                OutlinedTextField(
                    value = p.payload, onValueChange = { p = p.copy(payload = it) }, label = { Text("Payload") },
                    placeholder = { Text("CONNECT [host_port] HTTP/1.1[crlf]Host: bug.example.com[crlf][crlf]") },
                    textStyle = MonoStyle, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                )
                Hint("Placeholder: [host] [port] [host_port] [crlf] [cr] [lf] [protocol] [ua] [split] (kirim terpisah dengan jeda 300 ms). Balasan 101 atau 2xx dianggap berhasil; kode lain ditolak dan tampil di log.")
                SshAccount(p, showPass, { showPass = !showPass }) { p = it }
            }

            Method.V2RAY -> V2Editor(p) { p = it }

            }
            if (m != Method.V2RAY) {
                Text("UDP (DNS & game)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pakai udpgw")
                        Text("Wajib agar DNS dan UDP lewat tunnel. Server harus menjalankan udpgw.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = p.useUdpgw, onCheckedChange = { p = p.copy(useUdpgw = it) })
                }
                if (p.useUdpgw) Field("Alamat udpgw", p.udpgw, "127.0.0.1:7300", mono = true) { p = p.copy(udpgw = it.trim()) }
            }

            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Batal") }
                Button(onClick = { onSave(p) }, enabled = p.canSave) { Text("Simpan") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, placeholder: String? = null, mono: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        placeholder = placeholder?.let { { Text(it) } }, textStyle = if (mono) MonoStyle else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Dropdown(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = current, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, v) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(v) })
            }
        }
    }
}


// ---------------------------------------------------------------- potongan form

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun PortField(label: String, value: Int, onChange: (Int) -> Unit) {
    Field(label, if (value == 0) "" else value.toString()) { onChange(it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) }
}

@Composable
private fun TlsFields(p: Profile, onChange: (Profile) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Pakai TLS (SSL)")
            Text("Aktifkan untuk port 443 / server di belakang CDN.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = p.useTls, onCheckedChange = { onChange(p.copy(useTls = it)) })
    }
    if (p.useTls) Field("SNI (opsional)", p.sni, "kosong = host header / host server") { onChange(p.copy(sni = it.trim())) }
}

@Composable
private fun SshAccount(p: Profile, showPass: Boolean, toggle: () -> Unit, onChange: (Profile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("User SSH", p.sshUser) { onChange(p.copy(sshUser = it.trim())) }
        OutlinedTextField(
            value = p.sshPass, onValueChange = { onChange(p.copy(sshPass = it)) }, label = { Text("Password SSH") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = toggle) { Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Tampilkan password") } },
        )
    }
}


// ---------------------------------------------------------------- DNS (dipakai SlowDNS dan V2Ray via SlowDNS)

@Composable
private fun NsFields(p: Profile, onChange: (Profile) -> Unit) {
    Field("Domain NS", p.nsDomain, "contoh: t.example.com") { onChange(p.copy(nsDomain = it.trim())) }
    if (p.nsDomainLooksLikeIp) Text("Domain NS harus berupa nama domain (NS tunnel), bukan alamat IP resolver.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Field("Public key (hex)", p.pubKey, mono = true) { onChange(p.copy(pubKey = it.trim())) }
}

@Composable
private fun ResolverFields(p: Profile, onChange: (Profile) -> Unit) {
    Text("Resolver", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Field("UDP (IP atau host resolver, boleh banyak, pisah koma)", p.resolverUdp, "8.8.8.8, host-server.contoh.com") { onChange(p.copy(resolverUdp = it.trim())) }
    Field("DoT (IP:port, boleh banyak, opsional)", p.resolverDot, "1.1.1.1:853") { onChange(p.copy(resolverDot = it.trim())) }
    Field("DoH (URL, opsional)", p.resolverDoh, "https://1.1.1.1/dns-query") { onChange(p.copy(resolverDoh = it.trim())) }
    Dropdown("Jalur resolver", p.transport.label, Transport.entries.map { it.label to it }) { onChange(p.copy(transport = it)) }
    Dropdown("Tipe record DNS", p.recordType.label, RecordType.entries.map { it.label to it }) { onChange(p.copy(recordType = it)) }
    if (p.recordType == RecordType.CUSTOM) {
        Field("Tipe kustom (nama atau angka tipe RR)", p.customType, "contoh: SRV, CNAME, MX, NS, A, AAAA, CAA atau angka", mono = true) { onChange(p.copy(customType = it.trim())) }
        if (p.customType.isNotBlank() && !p.customTypeValid) Text("Isi dengan nama (huruf/angka) atau angka 1-65535.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    Hint("Isi beberapa resolver (pisah koma) agar otomatis pindah bila satu diblokir operator. Otomatis mencoba TXT lalu NULL di tiap jalur. SPF, KEY, OPENPGPKEY, URI, dan Kustom eksperimental (Kustom menerima angka tipe RR, payload mentah di RDATA): dnstt-server harus dijalankan dengan -type yang sama, dan sebagian resolver memfilter tipe tertentu.")
}

@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ---------------------------------------------------------------- V2Ray: tempel link, edit manual, opsi SlowDNS

@Composable
private fun V2Editor(p: Profile, onChange: (Profile) -> Unit) {
    // salinan lokal supaya field boleh sementara kosong/tidak valid saat diketik tanpa membuat editor hilang
    var l by remember { mutableStateOf(V2Ray.parse(p.v2rayLink)) }
    var advanced by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = p.v2rayLink,
        onValueChange = { text -> onChange(p.copy(v2rayLink = text.trim())); V2Ray.parse(text)?.let { l = it } },
        label = { Text("Link vmess:// vless:// trojan://") },
        textStyle = MonoStyle, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(),
    )
    val parsed = p.v2
    when {
        p.v2rayLink.isBlank() -> Hint("Tempel link berbagi dari penyedia server. Mendukung TCP, WebSocket, gRPC, HTTPUpgrade dengan TLS atau Reality.")
        parsed != null -> Text("✓ ${V2Ray.describe(parsed)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        else -> Text("Link tidak dikenali atau tidak lengkap.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }

    val cur = l
    if (cur != null) {
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Sembunyikan editor" else "Edit manual (port, host, path, TLS)") }
        if (advanced) {
            fun upd(n: V2Link) { l = n; onChange(p.copy(v2rayLink = V2Ray.toLink(n))) }
            Field("Nama", cur.name) { upd(cur.copy(name = it)) }
            Field("Alamat server", cur.host) { upd(cur.copy(host = it.trim())) }
            PortField("Port", cur.port) { upd(cur.copy(port = it)) }
            Field(if (cur.proto == "trojan") "Password" else "UUID", cur.id, mono = true) { upd(cur.copy(id = it.trim())) }
            if (cur.proto == "vmess") PortField("Alter ID", cur.aid) { upd(cur.copy(aid = it)) }
            if (cur.proto == "vless") Field("Flow (opsional)", cur.flow, "xtls-rprx-vision") { upd(cur.copy(flow = it.trim())) }
            Dropdown("Jaringan", cur.network, listOf("tcp", "ws", "grpc", "httpupgrade").map { it to it }) { upd(cur.copy(network = it)) }
            Dropdown("Keamanan", cur.security, listOf("none", "tls", "reality").map { it to it }) { upd(cur.copy(security = it)) }
            if (cur.network == "grpc") {
                Field("Service name", cur.serviceName) { upd(cur.copy(serviceName = it.trim())) }
            } else if (cur.network != "tcp") {
                Field("Host header (opsional)", cur.hostHeader, "kosong = SNI / alamat server") { upd(cur.copy(hostHeader = it.trim())) }
                Field("Path", cur.path, "/") { upd(cur.copy(path = it.trim())) }
            }
            if (cur.security != "none") {
                Field("SNI (opsional)", cur.sni, "kosong = host header / alamat server") { upd(cur.copy(sni = it.trim())) }
                Field("Fingerprint", cur.fingerprint, "chrome") { upd(cur.copy(fingerprint = it.trim())) }
                Field("ALPN (opsional)", cur.alpn, "h2,http/1.1") { upd(cur.copy(alpn = it.trim())) }
            }
            if (cur.security == "tls") ToggleRow("Izinkan sertifikat tidak valid", "Hanya untuk server sendiri / pengujian.", cur.allowInsecure) { upd(cur.copy(allowInsecure = it)) }
            if (cur.security == "reality") {
                Field("Public key", cur.publicKey, mono = true) { upd(cur.copy(publicKey = it.trim())) }
                Field("Short ID", cur.shortId, mono = true) { upd(cur.copy(shortId = it.trim())) }
                Field("SpiderX (opsional)", cur.spiderX, "/") { upd(cur.copy(spiderX = it.trim())) }
            }
        }
    }

    ToggleRow(
        "Lewat SlowDNS",
        "Hubungkan ke server V2Ray memakai tunnel DNS (dnstt). Alamat dan port di link diganti jalur lokal; host dan SNI tetap dipakai.",
        p.v2ViaDns,
    ) { onChange(p.copy(v2ViaDns = it)) }
    if (p.v2ViaDns) {
        NsFields(p, onChange)
        ResolverFields(p, onChange)
        Hint("Server dnstt harus meneruskan ke port Xray/V2Ray di server (mis. 127.0.0.1:443). DNS/UDP aplikasi tidak lewat V2Ray.")
    }
}
