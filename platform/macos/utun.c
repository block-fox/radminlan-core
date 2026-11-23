#include <errno.h>
#include <net/if_utun.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/kern_control.h>
#include <sys/socket.h>
#include <sys/sys_domain.h>

#include "../packet-stream.h"

static ssize_t read_packet(int fd, void *buffer, size_t capacity) {
    unsigned char frame[MTU + 5];
    ssize_t length = recv(fd, frame, sizeof(frame), 0);
    if (length <= 0) return length;
    if (length < 4 || (size_t)(length - 4) > capacity) { errno = EMSGSIZE; return -1; }
    uint32_t family;
    memcpy(&family, frame, 4);
    if (ntohl(family) != AF_INET || length == 4) { errno = EINTR; return -1; }
    memcpy(buffer, frame + 4, (size_t)(length - 4));
    return length - 4;
}

static ssize_t write_packet(int fd, const void *packet, size_t length) {
    unsigned char frame[MTU + 4];
    uint32_t family = htonl(AF_INET);
    memcpy(frame, &family, 4);
    memcpy(frame + 4, packet, length);
    ssize_t sent = send(fd, frame, length + 4, 0);
    return sent < 0 ? -1 : sent - 4;
}

int main(int argc, char **argv) {
    if (argc != 2 || strncmp(argv[1], "utun", 4) != 0 || !argv[1][4] ||
        strspn(argv[1] + 4, "0123456789") != strlen(argv[1] + 4)) {
        fputs("Usage: radminlan-utun utunNUMBER\n", stderr);
        return 2;
    }
    unsigned long unit = strtoul(argv[1] + 4, NULL, 10);
    if (unit >= 65535) return 2;
    int device = socket(PF_SYSTEM, SOCK_DGRAM, SYSPROTO_CONTROL);
    if (device < 0) { perror("open utun control socket"); return 1; }
    struct ctl_info info = {0};
    memcpy(info.ctl_name, UTUN_CONTROL_NAME, sizeof(UTUN_CONTROL_NAME));
    if (ioctl(device, CTLIOCGINFO, &info) < 0) {
        perror("find utun control");
        close(device);
        return 1;
    }
    struct sockaddr_ctl address = {0};
    address.sc_len = sizeof(address);
    address.sc_family = AF_SYSTEM;
    address.ss_sysaddr = AF_SYS_CONTROL;
    address.sc_id = info.ctl_id;
    address.sc_unit = (uint32_t)(unit + 1);
    if (connect(device, (struct sockaddr *)&address, sizeof(address)) < 0) {
        perror("create utun");
        close(device);
        return 1;
    }
    int result = pump_packets(device, read_packet, write_packet);
    close(device);
    return result < 0 ? 1 : 0;
}
