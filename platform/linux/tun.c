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

#define MTU 1500

static int read_exact(int fd, void *buffer, size_t length) {
    unsigned char *bytes = buffer;
    size_t offset = 0;
    while (offset < length) {
        ssize_t count = read(fd, bytes + offset, length - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return count == 0 && offset == 0 ? 0 : -1;
        offset += (size_t)count;
    }
    return 1;
}

static int write_exact(int fd, const void *buffer, size_t length) {
    const unsigned char *bytes = buffer;
    while (length) {
        ssize_t count = write(fd, bytes, length);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) return -1;
        bytes += count;
        length -= (size_t)count;
    }
    return 0;
}

static int pump(int device) {
    unsigned char packet[MTU + 1];
    struct pollfd fds[] = {{STDIN_FILENO, POLLIN, 0}, {device, POLLIN, 0}};
    // Startup marker goes to stdout only after the system interface is open.
    if (write_exact(STDOUT_FILENO, "RLT1", 4) < 0) return -1;
    for (;;) {
        int ready = poll(fds, 2, -1);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return -1;
        if (fds[0].revents & POLLIN) {
            uint16_t length;
            int got = read_exact(STDIN_FILENO, &length, sizeof(length));
            if (got <= 0) return got;
            size_t count = ntohs(length);
            if (count < 20 || count > MTU || read_exact(STDIN_FILENO, packet, count) != 1) return -1;
            ssize_t sent;
            do { sent = write(device, packet, count); } while (sent < 0 && errno == EINTR);
            if (sent < 0 || (size_t)sent != count) return -1;
        } else if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) return 0;
        if (fds[1].revents & POLLIN) {
            ssize_t count = read(device, packet, sizeof(packet));
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return -1;
            if (count < 20 || count > MTU || (packet[0] >> 4) != 4) continue;
            uint16_t length = htons((uint16_t)count);
            if (write_exact(STDOUT_FILENO, &length, sizeof(length)) < 0 ||
                write_exact(STDOUT_FILENO, packet, (size_t)count) < 0) return -1;
        } else if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    }
}

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
    int result = pump(device);
    close(device);
    if (result < 0) fputs("TUN packet stream closed after an I/O error\n", stderr);
    return result < 0 ? 1 : 0;
}
