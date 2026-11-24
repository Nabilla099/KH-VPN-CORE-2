package com.khvpn.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.khvpn.app.ui.KhRoot
import com.khvpn.app.vpn.KhVpnService
import com.khvpn.app.vpn.TunnelBus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = (application as KhApp).store
        setContent {
            // Alur izin: notifikasi (Android 13+) -> izin VPN -> start service
            var pendingProfile by remember { mutableStateOf<String?>(null) }

            val vpnPerm = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                if (r.resultCode == Activity.RESULT_OK) KhVpnService.start(this, pendingProfile)
            }
            fun askVpn(id: String?) {
                pendingProfile = id
                val i: Intent? = VpnService.prepare(this)
                if (i == null) KhVpnService.start(this, id) else vpnPerm.launch(i)
            }
            val needPerm by TunnelBus.needVpnPermission.collectAsState()
            LaunchedEffect(needPerm) {
                if (needPerm) { TunnelBus.consumeVpnPermission(); askVpn(pendingProfile) }
            }
            val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askVpn(pendingProfile) }

            KhRoot(
                store = store,
                onConnect = { id ->
                    pendingProfile = id
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS) else askVpn(id)
                },
                onDisconnect = { KhVpnService.stop(this) },
            )
        }
    }
}
