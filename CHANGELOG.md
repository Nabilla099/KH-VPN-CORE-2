# Changelog

## 2.2.1

- SlowDNS: kolom resolver UDP kini menerima nama host (mis. host server SlowDNS dengan port 53). Nama host diubah ke IP
  oleh aplikasi sebelum dnstt dijalankan, karena binary dnstt tidak punya resolver DNS sistem. Dengan ini server SlowDNS
  bisa dipakai langsung sebagai resolver, tanpa lewat resolver publik.
- SlowDNS: entri DoT berupa nama host dilewati dengan pesan jelas (dnstt tidak bisa me-resolve-nya), tidak lagi memicu
  siklus sambung ulang.
- Build: input `dnstt_ref` pada workflow untuk memilih revisi dnstt (kosong = terbaru). Revisi yang dibuild dicatat ke APK
  dan ditampilkan di log aplikasi ("Binary dnstt: <hash> <tanggal>").
- SlowDNS: sebelum login SSH, aplikasi menunggu banner SSH lewat tunnel DNS (maks. 30 detik) untuk memastikan tipe record,
  public key, dan domain NS cocok. Rute yang salah gagal cepat dengan pesan yang jelas. Setelah banner diterima, login SSH
  diberi waktu hingga 90 detik (sebelumnya 32 detik), karena kex lewat DNS butuh banyak putaran kecil.
- SlowDNS: kolom DoT yang berisi domain NS dilewati otomatis.
- SlowDNS: pilihan tipe record **Kustom**. Isi nama (NS, CNAME, MX, SRV, A, AAAA, CAA, dan tipe lain yang sudah ada) atau angka tipe RR (1-65535) yang dikirim sebagai `-type` ke dnstt;
  payload ditaruh mentah di RDATA. Server dnstt harus memakai build patch yang sama dengan `-type` yang sama.
- SlowDNS: peringatan di log bila kolom DoT memakai port 53 (DoT biasanya 853).
- SSH + WebSocket / Payload: deteksi mode (raw atau bingkai WebSocket) kini menunggu data pertama server hingga 8 detik
  (sebelumnya 1,5 detik). Di jaringan seluler yang lambat, bingkai WebSocket sebelumnya terbaca sebagai SSH rusak,
  login gagal, dan koneksi diulang terus (log "101 Switching Protocols" berulang).
- Port lokal payload dibuka dengan reuse-address sebelum bind, sehingga sambung ulang tidak lagi gagal dengan
  "Port lokal tidak bisa dipakai" yang menghentikan semua percobaan berikutnya.
- Tunnel menahan wake lock CPU dan Wi-Fi selama aktif. Saat layar mati, keepalive tidak lagi telat sehingga server
  atau CDN tidak menutup koneksi idle.
- V2Ray / semua metode: kegagalan membuat antarmuka VPN dicoba ulang beberapa kali dan alasan sebenarnya dicatat di log.
  Bila izin VPN memang belum diberikan, dialog izin sistem ditampilkan otomatis dan koneksi dilanjutkan setelah disetujui.

## 2.2.0

- Rilis otomatis: setiap push ke `main` membuat GitHub Release berisi APK release (R8 + shrink, bukan debug).
  Tidak perlu keystore sendiri; build ditandatangani keystore bawaan repo supaya update menimpa versi lama.
- APK per ABI (arm64-v8a, armeabi-v7a, x86_64, x86) + universal, lebih kecil untuk diunduh. Ada SHA256SUMS dan
  verifikasi tanda tangan di CI.
- SlowDNS: kolom resolver UDP/DoT/DoH boleh berisi beberapa alamat (pisah koma). Bila satu resolver diblokir
  operator, aplikasi otomatis pindah ke berikutnya, dan rute terakhir yang berhasil dicoba lebih dulu.
- SlowDNS: batas waktu login SSH diperpanjang (handshake lewat DNS lambat), dan label rute menampilkan resolver.
- SSH (SlowDNS dan Payload): bila sesi SSH putus tetapi tunnel masih hidup, aplikasi login ulang cepat TANPA
  memutus VPN. Status tetap "Terhubung", sehingga tidak ada lagi bolak-balik putus/sambung ("switching").
- Keepalive SSH lebih rapat untuk Payload/WebSocket (15 dtk), dengan cadangan metode dan peringatan di log bila
  pustaka tidak mendukungnya. TCP keepalive diaktifkan pada koneksi payload.
- Log putus sekarang menyertakan lama sesi aktif, untuk membantu mendiagnosis koneksi yang sering terputus.

## 2.1.1

- Sambung ulang otomatis tanpa batas setelah pernah tersambung (sebelumnya berhenti setelah 5 kali dan hitungannya
  tidak pernah direset). Jeda bertambah bertahap 3-15 detik, menunggu jaringan kembali bila offline, dan berhenti
  hanya untuk error fatal seperti password SSH salah.
- SSH + Payload / WebSocket: balasan HTTP berlapis (mis. "200 Connection established" lalu "101 Switching Protocols")
  kini dibuang semuanya sebelum diteruskan ke klien SSH. Sebelumnya header kedua dikira banner SSH sehingga login gagal
  dan koneksi diulang terus ("101 Switching Protocols" muncul berulang di log).
- Mode framing WebSocket penuh (RFC 6455) terdeteksi otomatis.
- Percobaan login SSH lewat payload diberi jeda 3 detik dan alasan tiap kegagalan dicatat di log.

## 2.1.0

- V2Ray bisa lewat SlowDNS (dnstt): aktifkan "Lewat SlowDNS" di profil V2Ray, isi domain NS, public key, dan resolver.
- Editor V2Ray manual: ubah port, alamat, UUID/password, jaringan, TLS/Reality, SNI, host, path tanpa menempel ulang link.
- Notifikasi menampilkan kecepatan unduh/unggah, total data, dan timer (bisa dimatikan di Pengaturan).
- Perbaikan tampilan: chip metode/tipe/jalur di kartu profil tidak lagi memanjang ke bawah di mode SlowDNS.
- Log: auto-scroll mengikuti baris terbaru, berhenti saat digeser manual, tombol untuk lanjut.
- Menu bisa digeser kiri/kanan selain diketuk.
- Tampilan Material 3 lebih modern (sudut lebih besar, app bar datar, warna dinamis aktif secara bawaan di Android 12+).
- Optimasi: animasi hanya berjalan saat dibutuhkan, Home tidak lagi rekomposisi tiap baris log, daftar log lebih kecil,
  proses Go (xray/dnstt) dibatasi memorinya (GOGC/GOMEMLIMIT), buffer koneksi Xray diperkecil, log akses Xray dimatikan.
- Rute dengan resolver kosong (mis. ":53") dilewati, bukan dijalankan dengan argumen rusak.
