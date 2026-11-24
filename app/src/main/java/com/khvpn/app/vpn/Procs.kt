package com.khvpn.app.vpn

import android.content.Context
import java.io.File

/** Pembungkus proses native dengan logging output. */
class Proc(val name: String, private val p: Process) {
    fun alive(): Boolean = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    fun exitCode(): Int? = try { p.exitValue() } catch (_: IllegalThreadStateException) { null }
    fun kill() { runCatching { p.destroy() } }
}

object Natives {
    fun dir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir
    fun dnstt(ctx: Context) = File(dir(ctx), "libdnstt.so")
    fun tun2socks(ctx: Context) = File(dir(ctx), "libtun2socks.so")
    fun t2sfd(ctx: Context) = File(dir(ctx), "libt2sfd.so")
    fun xray(ctx: Context) = File(dir(ctx), "libxray.so")

    /** Jalankan proses, alirkan stdout+stderr ke log. */
    fun spawn(name: String, cmd: List<String>): Proc {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        if (name == "xray" || name == "dnstt") {
            // Runtime Go: GC lebih sering + kembalikan memori ke OS cepat -> RAM jauh lebih kecil di HP murah
            pb.environment().apply {
                put("GOGC", "40")
                put("GOMEMLIMIT", if (name == "xray") "96MiB" else "48MiB")
                put("GODEBUG", "madvdontneed=1")
            }
        }
        val p = pb.start()
        Thread({
            runCatching {
                p.inputStream.bufferedReader().forEachLine { line ->
                    if (line.isNotBlank()) TunnelBus.log('N', "$name: ${line.take(300)}")
                }
            }
        }, "log-$name").apply { isDaemon = true }.start()
        return Proc(name, p)
    }

    /** Ambil teks bantuan sebuah binary (untuk mendeteksi flag yang didukung). */
    fun helpText(bin: File, vararg args: String): String {
        if (!bin.exists()) return ""
        return runCatching {
            val p = ProcessBuilder(listOf(bin.absolutePath) + args).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val t = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (sb.length < 16000) sb.appendLine(it) } } }
            t.isDaemon = true; t.start(); t.join(3000)
            runCatching { p.destroy() }
            sb.toString()
        }.getOrDefault("")
    }
}
