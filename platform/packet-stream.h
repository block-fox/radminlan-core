#ifndef RADMINLAN_PACKET_STREAM_H
#define RADMINLAN_PACKET_STREAM_H

#include <arpa/inet.h>
#include <errno.h>
#include <poll.h>
#include <stdint.h>
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

typedef ssize_t (*packet_reader)(int, void *, size_t);
typedef ssize_t (*packet_writer)(int, const void *, size_t);

static int pump_packets(int device, packet_reader read_packet, packet_writer write_packet) {
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
            do { sent = write_packet(device, packet, count); } while (sent < 0 && errno == EINTR);
            if (sent < 0 || (size_t)sent != count) return -1;
        } else if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) return 0;
        if (fds[1].revents & POLLIN) {
            ssize_t count = read_packet(device, packet, sizeof(packet));
            if (count < 0 && errno == EINTR) continue;
            if (count <= 0) return -1;
            if (count < 20 || count > MTU || (packet[0] >> 4) != 4) continue;
            uint16_t length = htons((uint16_t)count);
            if (write_exact(STDOUT_FILENO, &length, sizeof(length)) < 0 ||
                write_exact(STDOUT_FILENO, packet, (size_t)count) < 0) return -1;
        } else if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    }
}

#endif
