from pathlib import Path
import platform
import select
import socket
import struct
import subprocess
import sys


# Uses a Linux network namespace or a temporary utun on a disposable macOS runner.
helper = str(Path(sys.argv[1]).resolve(strict=True))
mac = platform.system() == 'Darwin'
iface = 'utun70' if mac else 'radminlan0'
local, remote = '26.77.1.1', '26.77.1.2'


def run(*args):
    subprocess.run(args, check=True)


def read_stream(stream, count):
    data = bytearray()
    while len(data) < count:
        assert select.select([stream], [], [], 5)[0], 'TUN helper did not provide bytes'
        chunk = stream.read(count - len(data))
        assert chunk, 'TUN helper closed early'
        data.extend(chunk)
    return bytes(data)


def checksum(data):
    value = sum(struct.unpack('!10H', data))
    while value >> 16:
        value = (value & 0xffff) + (value >> 16)
    return ~value & 0xffff


if not mac:
    run('ip', 'tuntap', 'add', 'dev', iface, 'mode', 'tun')
    run('ip', 'address', 'add', local + '/24', 'dev', iface)
    run('ip', 'link', 'set', 'dev', iface, 'mtu', '1500', 'up')
process = subprocess.Popen([helper, iface], stdin=subprocess.PIPE, stdout=subprocess.PIPE, bufsize=0)
try:
    assert read_stream(process.stdout, 4) == b'RLT1'
    if mac:
        run('ifconfig', iface, 'inet', local, remote, 'netmask', '255.255.255.255', 'mtu', '1500', 'up')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as app:
        app.settimeout(5)
        app.bind((local, 31577))
        app.sendto(b'radmin-out', (remote, 31578))
        while True:
            (length,) = struct.unpack('!H', read_stream(process.stdout, 2))
            outgoing = read_stream(process.stdout, length)
            if outgoing.endswith(b'radmin-out'):
                break
        assert outgoing[9] == 17 and socket.inet_ntoa(outgoing[16:20]) == remote
        data = b'radmin-in'
        udp = struct.pack('!HHHH', 31578, 31577, 8 + len(data), 0) + data
        header = struct.pack('!BBHHHBBH4s4s', 0x45, 0, 20 + len(udp), 0, 0, 64, 17, 0,
                             socket.inet_aton(remote), socket.inet_aton(local))
        header = header[:10] + struct.pack('!H', checksum(header)) + header[12:]
        process.stdin.write(struct.pack('!H', len(header + udp)) + header + udp)
        assert app.recvfrom(64) == (data, (remote, 31578))
    process.stdin.close()
    assert process.wait(timeout=5) == 0
    print(('MACOS_UTUN' if mac else 'LINUX_TUN') + '_BIDIRECTIONAL_OK')
finally:
    if process.poll() is None:
        process.kill()
        process.wait(timeout=5)
