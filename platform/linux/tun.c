#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <net/if.h>
#include <linux/if_tun.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "../packet-stream.h"

int main(int argc, char **argv) {
    if (argc != 2 || strlen(argv[1]) < 1 || strlen(argv[1]) >= IFNAMSIZ ||
        strspn(argv[1], "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.") != strlen(argv[1])) {
        fputs("Usage: radminlan-tun EXISTING_TUN_NAME\n", stderr);
        return 2;
    }
    if (if_nametoindex(argv[1]) == 0) {
        fputs("The TUN interface must already exist\n", stderr);
        return 1;
    }
    int device = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
    if (device < 0) { perror("open /dev/net/tun"); return 1; }
    struct ifreq request = {0};
    request.ifr_flags = IFF_TUN | IFF_NO_PI;
    memcpy(request.ifr_name, argv[1], strlen(argv[1]) + 1);
    if (ioctl(device, TUNSETIFF, &request) < 0) {
        perror("attach TUN");
        close(device);
        return 1;
    }
    int result = pump_packets(device, read, write);
    close(device);
    if (result < 0) fputs("TUN packet stream closed after an I/O error\n", stderr);
    return result < 0 ? 1 : 0;
}
