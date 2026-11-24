package com.khvpn.app.ui.screens

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khvpn.app.BuildConfig
import com.khvpn.app.data.Store
import com.khvpn.app.data.ThemeMode
import com.khvpn.app.ui.ScreenFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(store: Store) {
    val s by store.settings.collectAsStateWithLifecycle()
    var showApps by remember { mutableStateOf(false) }

    ScreenFrame("Pengaturan") {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Section("Tampilan")
            ThemePicker(s.themeMode) { m -> store.update { it.copy(themeMode = m) } }
            if (Build.VERSION.SDK_INT >= 31) {
                SwitchRow("Warna dinamis", "Ikuti warna wallpaper (Android 12+)", s.dynamicColor) { v -> store.update { it.copy(dynamicColor = v) } }
            }

            Section("Jaringan")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Num("DNS 1", s.dns1, Modifier.weight(1f), text = true) { v -> store.update { it.copy(dns1 = v) } }
                Num("DNS 2", s.dns2, Modifier.weight(1f), text = true) { v -> store.update { it.copy(dns2 = v) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Num("Port SOCKS", s.socksPort.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { n -> store.update { it.copy(socksPort = n) } } }
                Num("Port dnstt lokal", s.localPort.toString(), Modifier.weight(1f)) { v -> v.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { n -> store.update { it.copy(localPort = n) } } }
            }
            Num("MTU", s.mtu.toString(), Modifier.fillMaxWidth()) { v -> v.toIntOrNull()?.takeIf { it in 576..1500 }?.let { n -> store.update { it.copy(mtu = n) } } }

            Section("Koneksi")
            SwitchRow("Sambung ulang otomatis", "Terus mencoba tanpa batas jika tunnel putus (berhenti bila password salah)", s.autoReconnect) { v -> store.update { it.copy(autoReconnect = v) } }
            SwitchRow("Kompresi SSH", "Hemat data, sedikit lebih berat di CPU", s.sshCompression) { v -> store.update { it.copy(sshCompression = v) } }
            SwitchRow("Kecepatan di notifikasi", "Tampilkan unduh/unggah dan timer (jeda saat layar mati)", s.showSpeed) { v -> store.update { it.copy(showSpeed = v) } }
            Row(Modifier.fillMaxWidth().clickable { showApps = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Filter aplikasi", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (s.bypassApps.isEmpty()) "Semua aplikasi lewat VPN" else "${s.bypassApps.size} aplikasi tidak lewat VPN",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("Tentang")
            Text("KH VPN ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Tunnel DNS: dnstt · SSH: sshlib (Trilead SSH-2) · Android ${Build.VERSION.RELEASE}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Perubahan DNS, port, dan MTU berlaku pada sambungan berikutnya.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer16()
        }
    }

    if (showApps) AppFilterDialog(s.bypassApps, onDismiss = { showApps = false }) { set -> store.update { it.copy(bypassApps = set) } }
}

@Composable private fun Spacer16() = androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemePicker(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        ThemeMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = current == m, onClick = { onPick(m) },
                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
            ) { Text(m.label) }
        }
    }
}

/** Field yang menyimpan teks lokal supaya mengetik tidak "loncat" saat nilai belum valid. */
@Composable
private fun Num(label: String, value: String, modifier: Modifier, text: Boolean = false, onCommit: (String) -> Unit) {
    var t by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = t, onValueChange = { t = it; onCommit(it.trim()) }, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = if (text) KeyboardType.Uri else KeyboardType.Number),
    )
}

private data class AppRow(val label: String, val pkg: String)

@Composable
private fun AppFilterDialog(selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val apps by produceState<List<AppRow>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(i, 0)
                .map { AppRow(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .filter { it.pkg != ctx.packageName }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        }
    }
    var picked by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter aplikasi") },
        text = {
            Column {
                Text("Aplikasi yang dicentang tidak lewat VPN (memakai koneksi biasa).", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Cari") }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                val list = apps?.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }
                if (list == null) Text("Memuat…") else LazyColumn(Modifier.height(320.dp)) {
                    items(list, key = { it.pkg }) { a ->
                        Row(
                            Modifier.fillMaxWidth().clickable { picked = if (a.pkg in picked) picked - a.pkg else picked + a.pkg },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = a.pkg in picked, onCheckedChange = { picked = if (it) picked + a.pkg else picked - a.pkg })
                            Column { Text(a.label, style = MaterialTheme.typography.bodyMedium); Text(a.pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked); onDismiss() }) { Text("Simpan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
