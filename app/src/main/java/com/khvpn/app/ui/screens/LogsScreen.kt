package com.khvpn.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khvpn.app.ui.ScreenFrame
import com.khvpn.app.ui.theme.MonoStyle
import com.khvpn.app.vpn.TunnelBus

@Composable
fun LogsScreen() {
    val logs by TunnelBus.logs.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val state = rememberLazyListState()

    // Auto-scroll: ikut baris terbaru. Berhenti saat pengguna menggeser sendiri, lanjut lagi saat sampai di dasar
    // atau saat tombol auto-scroll / panah bawah ditekan.
    var follow by remember { mutableStateOf(true) }
    val atEnd by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(atEnd) { if (atEnd) follow = true }
    LaunchedEffect(logs.size, follow) { if (follow && logs.isNotEmpty()) state.scrollToItem(logs.lastIndex) }

    ScreenFrame(
        title = "Log",
        fab = {
            if (!follow && logs.isNotEmpty()) SmallFloatingActionButton(onClick = { follow = true }) { Icon(Icons.Default.KeyboardArrowDown, "Ke baris terbaru") }
        },
        actions = {
            IconButton(onClick = { follow = !follow }) {
                Icon(
                    Icons.Default.VerticalAlignBottom, if (follow) "Auto-scroll aktif" else "Auto-scroll mati",
                    tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                // password tidak pernah masuk ke log, tapi domain/IP bisa: pastikan kamu nyaman membagikannya
                cm.setPrimaryClip(ClipData.newPlainText("KH VPN log", logs.joinToString("\n") { "${it.time} ${it.level} ${it.text}" }))
                Toast.makeText(ctx, "Log disalin", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, "Salin log") }
            IconButton(onClick = { TunnelBus.clearLogs() }) { Icon(Icons.Default.DeleteSweep, "Bersihkan log") }
        },
    ) {
        if (logs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Log kosong", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Log muncul saat kamu menyambung.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(logs, key = { it.id }) { l ->
                    val color = when (l.level) {
                        'E' -> MaterialTheme.colorScheme.error
                        'W' -> MaterialTheme.colorScheme.tertiary
                        'N' -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Text("${l.time}  ${l.text}", style = MonoStyle, color = color)
                }
            }
        }
    }
}
