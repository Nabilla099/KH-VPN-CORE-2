package com.khvpn.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khvpn.app.data.Method
import com.khvpn.app.data.Profile
import com.khvpn.app.data.RecordType
import com.khvpn.app.data.Store
import com.khvpn.app.data.Transport
import com.khvpn.app.ui.ScreenFrame
import com.khvpn.app.ui.theme.MonoStyle
import com.khvpn.app.vpn.Phase
import com.khvpn.app.vpn.fmtRate
import com.khvpn.app.vpn.Routes
import com.khvpn.app.vpn.TunnelBus
import com.khvpn.app.vpn.TunnelUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(store: Store, onConnect: (String?) -> Unit, onDisconnect: () -> Unit, onOpenProfiles: () -> Unit) {
    val ui by TunnelBus.state.collectAsStateWithLifecycle()
    val profiles by store.profiles.collectAsStateWithLifecycle()
    val selectedId by store.selectedId.collectAsStateWithLifecycle()
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    val ctx = LocalContext.current
    val typeOk by produceState<Boolean?>(null) { value = withContext(Dispatchers.IO) { Routes.supportsType(ctx) } }

    val onDial = {
        when {
            ui.active -> onDisconnect()
            selected == null || !selected.isValid -> onOpenProfiles()
            else -> onConnect(selected.id)
        }
    }

    ScreenFrame("KH VPN") {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 760.dp
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Dial(ui, 240.dp, onDial)
                        StatusText(ui)
                        Stages(ui, selected)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ProfileCard(store, profiles, selected, ui.active, typeOk, onOpenProfiles)
                        LogPreview()
                    }
                }
            } else {
                val dial = minOf(maxWidth * 0.62f, 260.dp)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Dial(ui, dial, onDial)
                    StatusText(ui)
                    ProfileCard(store, profiles, selected, ui.active, typeOk, onOpenProfiles)
                    Stages(ui, selected)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- dial

@Composable
private fun Dial(ui: TunnelUi, dial: Dp, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val connected = ui.phase == Phase.Connected
    val failed = ui.phase == Phase.Failed
    val ring by animateColorAsState(
        when { connected -> cs.primary; failed -> cs.error; ui.busy -> cs.tertiary; else -> cs.outlineVariant },
        animationSpec = tween(400), label = "ring",
    )
    val fill by animateColorAsState(if (connected) cs.primary else cs.surfaceContainerHigh, animationSpec = tween(350), label = "fill")
    val content by animateColorAsState(if (connected) cs.onPrimary else cs.onSurface, animationSpec = tween(350), label = "content")
    val label = when {
        connected -> "Terhubung"; ui.phase == Phase.Stopping -> "Memutuskan"; ui.busy -> "Menyambung"
        failed -> "Coba lagi"; else -> "Sambungkan"
    }
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "press")

    Box(Modifier.size(dial), contentAlignment = Alignment.Center) {
        val track = cs.outlineVariant
        // cincin statis: tidak menganimasikan apa pun saat siaga (hemat baterai)
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 10.dp.toPx()
            drawArc(if (ui.busy) track else ring, 0f, 360f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
        }
        if (ui.busy) SpinnerArc(ring) else if (connected) Pulse(ring)
        Surface(
            onClick = onClick, shape = CircleShape, color = fill, contentColor = content, interactionSource = src,
            modifier = Modifier.size(dial * 0.76f).graphicsLayer { scaleX = press; scaleY = press },
        ) {
            Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(dial * 0.24f))
                Spacer(Modifier.height(6.dp))
                AnimatedContent(
                    targetState = label,
                    transitionSpec = { (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 2 }) togetherWith (fadeOut(tween(100)) + slideOutVertically(tween(100)) { -it / 2 }) },
                    label = "dial-label",
                ) { Text(it, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun SpinnerArc(color: Color) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 10.dp.toPx()
        drawArc(color, angle, 110f, false, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun Pulse(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearOutSlowInEasing), RepeatMode.Restart), label = "p")
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius = size.width * (0.38f + 0.12f * p), style = Stroke(3.dp.toPx()))
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

@Composable
private fun StatusText(ui: TunnelUi) {
    val now by produceState(System.currentTimeMillis(), ui.connectedAt) {
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    AnimatedContent(
        targetState = ui.phase,
        transitionSpec = { (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 3 }) togetherWith (fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 3 }) },
        label = "status",
        modifier = Modifier.fillMaxWidth(),
    ) { phase ->
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        when (phase) {
            Phase.Connected -> {
                Text(fmt(now - ui.connectedAt), style = MaterialTheme.typography.headlineMedium)
                Text("Lewat ${ui.route}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                SpeedRow()
            }
            Phase.Failed -> Text(
                ui.detail.ifBlank { "Gagal tersambung" }, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            )
            Phase.Idle -> Text("Siap. Ketuk tombol untuk menyambung.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            else -> Text(ui.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
    }
}

@Composable
private fun SpeedRow() {
    val sp by TunnelBus.speed.collectAsStateWithLifecycle()
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text("↓ ${fmtRate(sp.down)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("↑ ${fmtRate(sp.up)}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

// ---------------------------------------------------------------- tahapan (urutan nyata, jadi bernomor)

private enum class Step { Pending, Active, Done }

@Composable
private fun Stages(ui: TunnelUi, profile: Profile?) {
    val method = profile?.method ?: Method.DNSTT
    val viaDns = profile?.v2ViaDns == true
    val titles = when (method) {
        Method.DNSTT -> listOf("Tunnel DNS", "Login SSH", "Antarmuka VPN")
        Method.SSH_WS -> listOf("Jalur WebSocket", "Login SSH", "Antarmuka VPN")
        Method.HTTP_PAYLOAD -> listOf("Payload HTTP", "Login SSH", "Antarmuka VPN")
        Method.V2RAY -> if (viaDns) listOf("Tunnel DNS", "Core V2Ray", "Antarmuka VPN") else listOf("Core V2Ray", "SOCKS5 lokal", "Antarmuka VPN")
    }
    val subs = when (method) {
        Method.DNSTT -> listOf("dnstt membuka jalur lewat resolver", "SSH-2 masuk, buka SOCKS5 lokal", "tun2socks meneruskan trafik perangkat")
        Method.SSH_WS -> listOf("Upgrade WebSocket ke server (TLS opsional)", "SSH-2 masuk, buka SOCKS5 lokal", "tun2socks meneruskan trafik perangkat")
        Method.HTTP_PAYLOAD -> listOf("Kirim payload, tunggu balasan server", "SSH-2 masuk, buka SOCKS5 lokal", "tun2socks meneruskan trafik perangkat")
        Method.V2RAY -> if (viaDns) listOf("dnstt membuka jalur lewat resolver", "Xray-core terhubung lewat tunnel DNS", "tun2socks meneruskan trafik perangkat")
            else listOf("Xray-core membaca konfigurasi dari link", "Menunggu port SOCKS5 terbuka", "tun2socks meneruskan trafik perangkat")
    }
    fun stateOf(i: Int): Step = when (ui.phase) {
        Phase.Connected -> Step.Done
        Phase.StartingDns -> if (i == 0) Step.Active else Step.Pending
        Phase.ConnectingSsh -> if (i == 0) Step.Done else if (i == 1) Step.Active else Step.Pending
        Phase.EstablishingVpn -> if (i < 2) Step.Done else Step.Active
        else -> Step.Pending
    }
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            titles.forEachIndexed { i, t ->
                val st = stateOf(i)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        AnimatedContent(
                            targetState = st,
                            transitionSpec = {
                                (scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)) + fadeIn(tween(180))) togetherWith
                                    (scaleOut(tween(120)) + fadeOut(tween(120)))
                            },
                            label = "step-$i",
                        ) { cur ->
                            when (cur) {
                                Step.Done -> Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(5.dp))
                                }
                                Step.Active -> CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                                Step.Pending -> Surface(
                                    shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(28.dp),
                                ) { Box(contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium) } }
                            }
                        }
                    }
                    Column {
                        Text(t, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (st == Step.Active && ui.detail.isNotBlank()) ui.detail else subs[i],
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- profil + pilihan tipe

private data class Opt<T>(val value: T, val text: String, val enabled: Boolean = true)

@Composable
private fun <T> ChoiceChip(label: String, current: String, options: List<Opt<T>>, enabled: Boolean, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true }, enabled = enabled,
            label = { Text("$label: $current", maxLines = 1, softWrap = false) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o.text) }, enabled = o.enabled, onClick = { open = false; onPick(o.value) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(
    store: Store, profiles: List<Profile>, selected: Profile?, locked: Boolean, typeOk: Boolean?, onOpenProfiles: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected == null) {
                Text("Belum ada profil", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tambahkan domain NS, public key, dan akun SSH dari penyedia server kamu.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenProfiles) { Text("Tambah profil") }
                return@Column
            }
            var menu by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected.name.ifBlank { "Tanpa nama" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(selected.endpoint, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (profiles.size > 1) Box {
                    IconButton(onClick = { menu = true }, enabled = !locked) { Icon(Icons.Default.ArrowDropDown, "Ganti profil") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        profiles.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name.ifBlank { p.endpoint }) }, onClick = { menu = false; store.select(p.id) })
                        }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(onClick = {}, enabled = false, label = { Text(selected.method.short, maxLines = 1, softWrap = false) })
                if (selected.method == Method.DNSTT || (selected.method == Method.V2RAY && selected.v2ViaDns)) {
                    if (selected.method == Method.V2RAY) AssistChip(onClick = {}, enabled = false, label = { Text("SlowDNS", maxLines = 1, softWrap = false) })
                    ChoiceChip(
                        "Tipe", selected.recordType.label.substringBefore(" ("),
                        RecordType.entries.map { t ->
                            val needsPatched = t.flag != null && t != RecordType.TXT
                            Opt(t, if (needsPatched && typeOk == false) "${t.label} (binary ini hanya TXT)" else t.label, !(needsPatched && typeOk == false))
                        },
                        enabled = !locked,
                    ) { store.save(selected.copy(recordType = it)) }
                    ChoiceChip(
                        "Jalur", selected.transport.label,
                        Transport.entries.map { Opt(it, it.label) }, enabled = !locked,
                    ) { store.save(selected.copy(transport = it)) }
                } else if (selected.method != Method.V2RAY) {
                    AssistChip(onClick = {}, enabled = false, label = { Text(selected.summary, maxLines = 1, softWrap = false) })
                }
            }
            if (!selected.isValid) Text(
                "Profil belum lengkap, buka tab Profil.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LogPreview() {
    val logs by TunnelBus.logs.collectAsStateWithLifecycle()
    val lines = remember(logs) { logs.takeLast(10).map { "${it.time} ${it.text}" } }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Aktivitas terbaru", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            if (lines.isEmpty()) Text("Belum ada aktivitas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            lines.forEach { Text(it, style = MonoStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
