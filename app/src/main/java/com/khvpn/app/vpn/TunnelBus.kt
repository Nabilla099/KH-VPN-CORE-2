package com.khvpn.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Phase { Idle, StartingDns, ConnectingSsh, EstablishingVpn, Connected, Stopping, Failed }

data class TunnelUi(
    val phase: Phase = Phase.Idle,
    val detail: String = "",
    val route: String = "",         // mis. "UDP · TXT"
    val connectedAt: Long = 0L,
) {
    val busy get() = phase == Phase.StartingDns || phase == Phase.ConnectingSsh ||
        phase == Phase.EstablishingVpn || phase == Phase.Stopping
    val active get() = busy || phase == Phase.Connected
}

data class LogLine(val time: String, val level: Char, val text: String, val id: Long = 0L)

/** Kecepatan (byte/detik) dan total (byte) sejak terhubung. */
data class Speed(val down: Long = 0, val up: Long = 0, val totalDown: Long = 0, val totalUp: Long = 0)

fun fmtRate(bytesPerSec: Long): String = fmtBytes(bytesPerSec) + "/s"

fun fmtBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.0f KB".format(Locale.US, b / 1024.0)
    b < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, b / 1048576.0)
    else -> "%.2f GB".format(Locale.US, b / 1073741824.0)
}

/** Jembatan state antara service dan UI. */
object TunnelBus {
    private const val MAX_LOGS = 400

    private val _state = MutableStateFlow(TunnelUi())
    val state: StateFlow<TunnelUi> = _state

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs

    private val _speed = MutableStateFlow(Speed())
    val speed: StateFlow<Speed> = _speed

    /** true = service butuh izin VPN dari pengguna; UI menampilkan dialog sistem lalu menyambung otomatis. */
    private val _needVpnPermission = MutableStateFlow(false)
    val needVpnPermission: StateFlow<Boolean> = _needVpnPermission
    fun requestVpnPermission() { _needVpnPermission.value = true }
    fun consumeVpnPermission() { _needVpnPermission.value = false }

    private val buf = ArrayDeque<LogLine>()
    private var seq = 0L
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun set(phase: Phase, detail: String = "", route: String = _state.value.route) {
        _state.value = TunnelUi(
            phase = phase, detail = detail, route = route,
            connectedAt = if (phase == Phase.Connected) (_state.value.connectedAt.takeIf { it > 0 } ?: System.currentTimeMillis()) else 0L,
        )
        if (phase != Phase.Connected) _speed.value = Speed()
    }

    fun setSpeed(s: Speed) { _speed.value = s }

    @Synchronized
    fun log(level: Char, text: String) {
        val l = LogLine(fmt.format(Date()), level, text, seq++)
        buf.addLast(l)
        while (buf.size > MAX_LOGS) buf.removeFirst()
        _logs.value = buf.toList()
    }

    /** Dipanggil saat sistem kekurangan memori: sisakan log terbaru saja. */
    @Synchronized
    fun trimLogs(keep: Int = 120) {
        if (buf.size <= keep) return
        while (buf.size > keep) buf.removeFirst()
        _logs.value = buf.toList()
    }

    fun i(t: String) = log('I', t)
    fun w(t: String) = log('W', t)
    fun e(t: String) = log('E', t)

    @Synchronized
    fun clearLogs() { buf.clear(); _logs.value = emptyList() }
}
