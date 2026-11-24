<div align="center">

<img src="docs/logo.svg" alt="KH VPN" width="420">

**Klien tunnel Android yang ringan, stabil, dan modern.**

![Android](https://img.shields.io/badge/Android-6.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Material 3](https://img.shields.io/badge/UI-Material%203-2FB59C?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-arm64%20%7C%20armv7%20%7C%20x86%20%7C%20x86__64-F2A93B?style=flat-square)

</div>

---

## Ringkasan

KH VPN adalah klien VPN Android berbasis tunnel yang dibangun ulang dari awal dengan antarmuka Jetpack Compose
dan arsitektur jaringan modern. Lalu lintas perangkat diteruskan melalui rantai
**transport → SSH-2 → SOCKS5 → tun2socks (+ udpgw)**, dengan sambung ulang otomatis dan perpindahan rute
tanpa memutus status koneksi.

## Fitur Utama

- **Beberapa metode koneksi** dalam satu profil: SlowDNS, SSH + WebSocket, SSH + HTTP Payload, dan V2Ray / Xray.
- **Antarmuka adaptif** untuk ponsel, tablet, dan perangkat lipat, dengan warna dinamis Material You.
- **Sambung ulang cerdas**: jeda bertahap, menunggu jaringan kembali, dan berhenti hanya pada kesalahan fatal.
- **Pemulihan sesi SSH tanpa memutus VPN**, sehingga status tetap *Terhubung* saat sesi diperbarui.
- **Multi-resolver** dengan failover otomatis dan penyimpanan rute terakhir yang berhasil.
- **Dukungan UDP** melalui udpgw atau SOCKS5 UDP, termasuk DNS.
- **Notifikasi informatif**: kecepatan unduh/unggah, total data, dan durasi sesi.
- **Log langsung** dengan auto-scroll yang bisa dijeda.
- **Ringan**: APK terpisah per arsitektur, R8 dan penyusutan sumber daya aktif.

## Metode Koneksi

| Metode | Kebutuhan di sisi server | Catatan |
|---|---|---|
| SlowDNS | Server dnstt, sshd, `badvpn-udpgw` | Tipe record TXT, NULL, SPF, KEY, OPENPGPKEY, URI, atau Kustom (angka tipe RR). Klien dan server harus memakai tipe yang sama. Jalur UDP :53, DoT :853, atau DoH. |
| SSH + WebSocket | Proxy WebSocket ke sshd, `badvpn-udpgw` | TLS dan SNI opsional. |
| SSH + HTTP Payload | sshd (langsung atau lewat HTTP proxy), `badvpn-udpgw` | Placeholder: `[host] [port] [host_port] [crlf] [cr] [lf] [protocol] [ua] [split]`. |
| V2Ray / Xray | Server vmess, vless, atau trojan | Impor lewat tautan berbagi. TCP, WS, gRPC, HTTPUpgrade dengan TLS atau Reality. Dapat dijalankan melalui SlowDNS. |

## Arsitektur

```
Aplikasi di perangkat
        │
   VpnService (TUN)
        │
    tun2socks ── udpgw
        │
     SOCKS5
        │
   SSH-2 / Xray
        │
 Transport: DNS · WebSocket · HTTP Payload · TLS
        │
     Server tujuan
```

## Spesifikasi

| | |
|---|---|
| Antarmuka | Jetpack Compose, Material 3 |
| SSH | sshlib |
| Versi Android | minSdk 23 (Android 6.0), targetSdk 35 |
| Arsitektur CPU | armeabi-v7a, arm64-v8a, x86, x86_64 |
| Bahasa | Kotlin, Go (komponen jaringan), C (tun2socks) |

## Memilih Berkas APK

| Berkas | Perangkat |
|---|---|
| `arm64-v8a` | Hampir semua ponsel 64-bit (sekitar 2017 ke atas) |
| `armeabi-v7a` | Ponsel 32-bit atau perangkat lama |
| `x86_64` / `x86` | Emulator dan Chromebook |
| `universal` | Semua arsitektur, ukuran terbesar |

Integritas berkas dapat diperiksa dengan `SHA256SUMS.txt` yang menyertai setiap rilis.

## Membangun dari Sumber

Prasyarat: JDK 17, Android SDK 35, Android NDK, Go 1.22+, dan Gradle 8.11+.

1. Bangun komponen native untuk setiap arsitektur (`dnstt`, `tun2socks`, dan opsional `xray`), lalu
   letakkan hasilnya di `app/src/main/jniLibs/<abi>/` dengan nama `libdnstt.so`, `libtun2socks.so`, dan `libxray.so`.
2. Jalankan:

   ```bash
   gradle :app:assembleRelease
   ```

3. APK dihasilkan di `app/build/outputs/apk/release/`.

Skrip bantu untuk komponen native tersedia di folder `tools/` dan `native/`.

## Catatan Teknis

- Binary `dnstt` dijalankan dari direktori pustaka native, sehingga pustaka diekstrak ke penyimpanan
  (`useLegacyPackaging`).
- Untuk DoT dan DoH gunakan alamat IP, bukan nama host, karena binary statis tidak memiliki resolver DNS sistem.
- Verifikasi host key SSH dinonaktifkan karena jalur sudah diautentikasi oleh kunci Noise pada transport
  dan alamat tujuan berupa loopback.
- Ekstensi record NULL, SPF, KEY, OPENPGPKEY, dan URI bersifat eksperimental; sebagian resolver publik
  memfilternya.

## Riwayat Perubahan

Lihat [CHANGELOG.md](CHANGELOG.md).

## Penafian

Gunakan hanya pada jaringan dan server yang Anda miliki atau berwenang untuk mengaksesnya, serta patuhi hukum
yang berlaku di wilayah Anda.
