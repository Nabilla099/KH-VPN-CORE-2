#!/usr/bin/env python3
"""
Patch dnstt (bamsoftware) supaya client & server bisa memilih tipe record DNS lewat flag -type
(TXT = bawaan, NULL = payload mentah di RDATA). Server dan client HARUS memakai tipe yang sama.

Skrip ini sengaja "gagal keras" (assert) bila pola kode tidak ditemukan, supaya CI
jatuh ke build dnstt asli (TXT saja) alih-alih menghasilkan binary yang setengah jadi.

Pemakaian: python3 patch_dnstt.py <direktori-dnstt>
"""
import glob, os, re, sys

root = sys.argv[1]
with open(os.path.join(root, "go.mod"), encoding="utf-8") as f_mod:
    mod = re.search(r"^module\s+(\S+)", f_mod.read(), re.M).group(1)

FLAG_HELP = "DNS record type: TXT (default), NULL, SPF, KEY, OPENPGPKEY, URI, NS, CNAME, MX, SRV, A, AAAA, CAA or a numeric RR type 1-65535 (non-TXT types carry raw RDATA). Server and client must match"

def rrtype_go(side: str) -> str:
    """rrtype.go: tipe RR yang dipilih lewat -type + codec RDATA-nya (tanpa menyentuh parser DNS asli)."""
    client = side == "client"
    imports = ('\t"errors"\n' if client else "") + '\t"strconv"\n'
    head = f'''package main

import (
{imports}\t"log"
\t"strings"

\t"{mod}/dns"
)
'''
    common = r'''
const (
	rrTypeNULL    uint16 = 10
	rrTypeKEY     uint16 = 25
	rrTypeOPENPGP uint16 = 61
	rrTypeSPF     uint16 = 99
	rrTypeURI     uint16 = 256
)

var rrTypeName string
var rrType uint16 = dns.RRTypeTXT

func initRRType() {
	var t uint16
	switch strings.ToUpper(rrTypeName) {
	case "", "TXT":
		t = dns.RRTypeTXT
	case "NULL":
		t = rrTypeNULL
	case "SPF":
		t = rrTypeSPF
	case "KEY":
		t = rrTypeKEY
	case "OPENPGPKEY":
		t = rrTypeOPENPGP
	case "URI":
		t = rrTypeURI
	case "NS":
		t = 2
	case "CNAME":
		t = 5
	case "MX":
		t = 15
	case "SRV":
		t = 33
	case "A":
		t = 1
	case "AAAA":
		t = 28
	case "CAA":
		t = 257
	default:
		n, err := strconv.ParseUint(rrTypeName, 10, 16)
		if err != nil || n == 0 {
			log.Fatalf("unsupported -type %q (use TXT, NULL, SPF, KEY, OPENPGPKEY, URI, NS, CNAME, MX, SRV, A, AAAA, CAA or a number 1-65535)", rrTypeName)
		}
		t = uint16(n)
	}
	rrType = t
}

// rrIsTXT: SPF memakai format RDATA yang sama dengan TXT (deretan character-string).
func rrIsTXT(t uint16) bool { return t == dns.RRTypeTXT || t == rrTypeSPF }

// rrHeader: field tetap di depan RDATA untuk tipe yang menuntutnya; sisanya payload mentah.
func rrHeader(t uint16) []byte {
	switch t {
	case rrTypeKEY:
		return []byte{0x00, 0x00, 0x03, 0x01} // flags, protocol, algorithm
	case rrTypeURI:
		return []byte{0x00, 0x01, 0x00, 0x01} // priority, weight
	}
	return nil // NULL, OPENPGPKEY: RDATA = payload
}
'''
    if client:
        tail = r'''
func decodeRData(t uint16, p []byte) ([]byte, error) {
	if rrIsTXT(t) {
		return dns.DecodeRDataTXT(p)
	}
	h := rrHeader(t)
	if len(p) < len(h) {
		return nil, errors.New("rdata terlalu pendek")
	}
	return p[len(h):], nil
}
'''
    else:
        tail = r'''
func encodeRData(t uint16, p []byte) []byte {
	if rrIsTXT(t) {
		return dns.EncodeRDataTXT(p)
	}
	h := rrHeader(t)
	out := make([]byte, 0, len(h)+len(p))
	out = append(out, h...)
	return append(out, p...)
}
'''
    return head + common + tail

def patch(side: str):
    d = os.path.join(root, f"dnstt-{side}")
    files = [f for f in glob.glob(os.path.join(d, "*.go")) if not f.endswith("rrtype.go")]
    assert files, f"tidak ada file .go di {d}"
    n_type = n_codec = n_flag = 0
    for f in files:
        with open(f, "r", encoding="utf-8") as fp:
            s = fp.read()
        o = s
        s, c = re.subn(r"\bdns\.RRTypeTXT\b", "rrType", s); n_type += c
        codec = ("dns.DecodeRDataTXT(", "decodeRData(rrType, ") if side == "client" else ("dns.EncodeRDataTXT(", "encodeRData(rrType, ")
        c = s.count(codec[0]); s = s.replace(codec[0], codec[1]); n_codec += c
        if "flag.Parse()" in s and n_flag == 0:
            s = s.replace(
                "flag.Parse()",
                f'flag.StringVar(&rrTypeName, "type", "TXT", "{FLAG_HELP}")\n\tflag.Parse()\n\tinitRRType()', 1)
            n_flag += 1
        if s != o:
            with open(f, "w", encoding="utf-8") as fp:
                fp.write(s)
    assert n_type >= 1, f"{side}: dns.RRTypeTXT tidak ditemukan"
    assert n_codec >= 1, f"{side}: fungsi encode/decode TXT tidak ditemukan"
    assert n_flag == 1, f"{side}: flag.Parse() tidak ditemukan"
    with open(os.path.join(d, "rrtype.go"), "w", encoding="utf-8") as fp:
        fp.write(rrtype_go(side))
    if side == "client":
        # Android tidak punya /etc/resolv.conf: resolver pure-Go jatuh ke [::1]:53 (ditolak).
        # Paksa lookup hostname (mis. host DoT) lewat UDP ke resolver publik.
        with open(os.path.join(d, "resolver_android.go"), "w", encoding="utf-8") as fp:
            fp.write('''package main

import (
	"context"
	"net"
	"time"
)

func init() {
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			if c, err := d.DialContext(ctx, "udp", "8.8.8.8:53"); err == nil {
				return c, nil
			}
			return d.DialContext(ctx, "udp", "1.1.1.1:53")
		},
	}
}
''')
    print(f"[patch] dnstt-{side}: {n_type} referensi tipe, {n_codec} codec, flag -type ditambahkan")

if __name__ == "__main__":
    patch("client")
    patch("server")