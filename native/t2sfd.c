/*
 * t2sfd: pembungkus kecil untuk tun2socks upstream (ambrop72/badvpn).
 *
 * Java ProcessBuilder menutup semua fd selain 0/1/2, jadi fd TUN dari VpnService tidak bisa
 * diwariskan langsung. Pembungkus ini membuat unix socket, menerima fd TUN lewat SCM_RIGHTS
 * (dikirim oleh Tun2Socks.sendFd di Kotlin), lalu exec tun2socks dengan env T2S_TUNFD/T2S_MTU
 * (dibaca oleh patch di tools/patch_tun2socks.py).
 *
 * Pemakaian: libt2sfd.so <sock-path> <mtu> <path-tun2socks> [argumen tun2socks...]
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 4) { fprintf(stderr, "usage: t2sfd <sock> <mtu> <tun2socks> [args...]\n"); return 2; }
    const char *path = argv[1];

    int srv = socket(AF_UNIX, SOCK_STREAM, 0);
    if (srv < 0) { perror("socket"); return 1; }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof(addr.sun_path)) { fprintf(stderr, "sock path terlalu panjang\n"); return 1; }
    strcpy(addr.sun_path, path);
    unlink(path);
    if (bind(srv, (struct sockaddr *)&addr, sizeof(addr)) < 0) { perror("bind"); return 1; }
    if (listen(srv, 1) < 0) { perror("listen"); return 1; }

    alarm(20); /* jangan menggantung kalau aplikasi tidak pernah mengirim fd */
    int c;
    do { c = accept(srv, NULL, NULL); } while (c < 0 && errno == EINTR);
    if (c < 0) { perror("accept"); return 1; }

    char data;
    char ctl[CMSG_SPACE(sizeof(int))];
    struct iovec iov = { &data, 1 };
    struct msghdr msg;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov; msg.msg_iovlen = 1;
    msg.msg_control = ctl; msg.msg_controllen = sizeof(ctl);
    if (recvmsg(c, &msg, 0) < 0) { perror("recvmsg"); return 1; }
    struct cmsghdr *cm = CMSG_FIRSTHDR(&msg);
    if (!cm || cm->cmsg_level != SOL_SOCKET || cm->cmsg_type != SCM_RIGHTS) {
        fprintf(stderr, "fd TUN tidak diterima\n"); return 1;
    }
    int tun;
    memcpy(&tun, CMSG_DATA(cm), sizeof(int));
    alarm(0);
    close(c); close(srv); unlink(path);

    int fl = fcntl(tun, F_GETFD);
    if (fl >= 0) fcntl(tun, F_SETFD, fl & ~FD_CLOEXEC);

    char buf[16];
    snprintf(buf, sizeof(buf), "%d", tun); setenv("T2S_TUNFD", buf, 1);
    setenv("T2S_MTU", argv[2], 1);

    execv(argv[3], &argv[3]);
    perror("execv tun2socks");
    return 1;
}
