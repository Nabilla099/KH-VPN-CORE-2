package com.khvpn.app.vpn

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.khvpn.app.data.Method
import com.khvpn.app.data.Profile
import com.khvpn.app.data.Settings
import java.io.File
import java.io.FileDescriptor
import java.io.IOException

object Tun2Socks {
    const val CLIENT_V4 = "26.26.26.1"
    const val ROUTER_V4 = "26.26.26.2"

    fun start(ctx: Context, s: Settings, p: Profile, sock: File): Proc {
        val bin = Natives.tun2socks(ctx)
        val wrapper = Natives.t2sfd(ctx)
        // Deteksi kemampuan binary dari --help, jangan menebak
        val help = Natives.helpText(bin, "--help")
        val hasUdpgw = help.contains("--udpgw-remote-server-addr")
        val legacySock = when {
            help.contains("--sock-path") -> "--sock-path"
            help.contains("--sock ") || help.contains("--sock\n") || help.contains("<sock_file>") -> "--sock"
            else -> null
        }
        sock.delete()

        val common = mutableListOf(
            "--netif-ipaddr", ROUTER_V4,
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", "127.0.0.1:${s.socksPort}",
            "--loglevel", "3",
        )
        if (p.method == Method.V2RAY) {
            // Server V2Ray tidak punya udpgw: UDP lewat SOCKS5 UDP ASSOCIATE milik Xray
            if (help.contains("--socks5-udp")) common += "--socks5-udp"
            else TunnelBus.w("tun2socks ini tidak mendukung --socks5-udp: DNS/UDP tidak akan lewat V2Ray")
        } else if (p.useUdpgw && p.udpgw.isNotBlank()) {
            if (hasUdpgw) {
                common += listOf("--udpgw-remote-server-addr", p.udpgw.trim(), "--udpgw-transparent-dns")
            } else {
                TunnelBus.w("tun2socks ini tidak mendukung udpgw: DNS/UDP tidak akan lewat tunnel (opsi dilewati)")
            }
        }

        val cmd: List<String> = if (legacySock != null) {
            // Fork Android (--sock / --sock-path): menerima fd TUN langsung lewat unix socket
            listOf(bin.absolutePath) + common + listOf("--tunmtu", s.mtu.toString(), legacySock, sock.absolutePath)
        } else if (wrapper.exists()) {
            // tun2socks upstream + pembungkus t2sfd yang menerima fd lalu exec tun2socks
            listOf(wrapper.absolutePath, sock.absolutePath, s.mtu.toString(), bin.absolutePath) + common + listOf("--tundev", "tun0")
        } else {
            error("tun2socks tidak punya --sock/--sock-path dan pembungkus libt2sfd.so tidak ada: tidak bisa menerima fd TUN")
        }
        return Natives.spawn("tun2socks", cmd)
    }

    /** Kirim fd TUN ke proses tun2socks lewat unix socket (SCM_RIGHTS). */
    fun sendFd(sock: File, fd: FileDescriptor) {
        var tries = 0
        while (true) {
            try {
                Thread.sleep(50L shl tries)
                LocalSocket().use { ls ->
                    ls.connect(LocalSocketAddress(sock.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
                    ls.setFileDescriptorsForSend(arrayOf(fd))
                    ls.outputStream.write(42)
                }
                return
            } catch (e: IOException) {
                if (tries >= 6) throw e
                tries++
            }
        }
    }
}
