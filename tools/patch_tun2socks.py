#!/usr/bin/env python3
"""
Patch tun2socks upstream (ambrop72/badvpn) supaya TUN bisa dibuka dari fd yang diwariskan
(env T2S_TUNFD, dipasang oleh native/t2sfd.c) alih-alih membuat device sendiri (butuh root).
Upstream sudah punya udpgw (--udpgw-remote-server-addr), jadi DNS/UDP bisa lewat SSH + badvpn-udpgw.

Gagal keras (assert) bila pola kode tidak ditemukan.
Pemakaian: python3 patch_tun2socks.py <direktori-badvpn>
"""
import os, re, sys

path = os.path.join(sys.argv[1], "tun2socks", "tun2socks.c")
with open(path, encoding="utf-8") as f:
    s = f.read()

call = re.compile(r"BTap_Init\s*\(\s*&device\s*,\s*&ss\s*,\s*options\.tundev\s*,\s*device_error_handler\s*,\s*NULL\s*,\s*1\s*\)")
assert call.search(s), "pemanggilan BTap_Init(&device, &ss, options.tundev, ...) tidak ditemukan"
s = call.sub("t2s_open_tun(&device, &ss, options.tundev)", s, count=1)

helper = '''
static int t2s_open_tun (BTap *dev, BReactor *reactor, char *tundev)
{
    const char *fd = getenv("T2S_TUNFD");
    if (fd && *fd) {
        const char *mtu = getenv("T2S_MTU");
        return BTap_InitWithFD(dev, reactor, atoi(fd), (mtu && *mtu) ? atoi(mtu) : 1500, device_error_handler, NULL, 1);
    }
    return BTap_Init(dev, reactor, tundev, device_error_handler, NULL, 1);
}

'''
m = re.search(r"\nint main\s*\(", s)
assert m, "int main tidak ditemukan"
s = s[:m.start()] + "\n" + helper + s[m.start():]
if "<stdlib.h>" not in s:
    s = s.replace("#include <stdio.h>", "#include <stdio.h>\n#include <stdlib.h>", 1)

with open(path, "w", encoding="utf-8") as f:
    f.write(s)
print("[patch] tun2socks: TUN dari fd (T2S_TUNFD) ditambahkan")
