# RadminLAN Core

This private repository contains the cross-platform Radmin-compatible VPN core used by RadminLAN when an embedded core artifact is supplied. The default user entry point is the `radminlan-core` CLI. The Minecraft mod consumes the same `CoreSession` and `TcpForwarder` APIs through an optional core JAR.

## Current scope

- Java compatibility layer sourced from OpenRad with MIT attribution retained in `vendor/openrad/LICENSE`.
- Persistent identity, authenticated coordinator session, public catalog, public/private network join, create, leave, delete, enable and disable operations, and member removal for network administrators.
- Direct TCP, reliable UDP and relay peer transport from the upstream-compatible engine.
- CLI network management and one-use loopback TCP forwarding for Minecraft.
- No system adapter is required for the CLI or Minecraft integration. The mod forwards virtual `26.x.x.x` game connections through the in-process peer engine.

## Build

Use JDK 21 or newer. On Windows run `.\gradlew.bat build`. On Linux and macOS run `./gradlew build`. Gradle creates the CLI JAR in `build/libs` and the CLI distributions in `build/distributions`.

For an offline check, pass `-PjunitStandalone=/absolute/path/to/junit-platform-console-standalone-1.11.4.jar --offline` with the Gradle distribution already cached.

## CLI

```powershell
java -jar build\libs\radminlan-core-0.1.0.jar --help
java -jar build\libs\radminlan-core-0.1.0.jar connect
```

The default profile is `.radminlan/core` under the current user’s home directory. `--profile` selects an isolated profile. A coordinator can be configured with `coordinator.properties` and `modulus.bin` in that profile. The CLI uses the official-compatible coordinator by default only after the user explicitly starts `connect`. Confirmed joins and explicit enable or disable choices are saved for the same profile. On reconnect these choices apply to existing memberships.

## Linux system TUN

Build the helper with `cc -std=c11 -O2 platform/linux/tun.c -o radminlan-tun`. Connect the CLI and read its assigned IPv4 from `status`. Grant your login user access to a separate TUN, give it the assigned address, and add routes only for the Radmin peers you want to reach:

```sh
sudo ip tuntap add dev radminlan0 mode tun user "$(id -un)"
sudo ip address add <local-virtual-ip>/32 dev radminlan0
sudo ip link set dev radminlan0 mtu 1500 up
sudo ip route add <peer-virtual-ip>/32 dev radminlan0
```

In the connected CLI, enter `tun radminlan0 /absolute/path/to/radminlan-tun`. The helper only attaches to the chosen existing interface. It exchanges unicast IPv4 packets for authenticated peers. Use `untun` to release the helper, then `sudo ip tuntap del dev radminlan0 mode tun` to remove the interface and its routes. The CLI also releases the helper when it disconnects or exits.

## macOS system utun

Build `cc -std=c11 -O2 platform/macos/utun.c -o radminlan-utun`. Choose an unused `utun` number and run the helper with the permissions needed to create the interface. The CLI accepts an executable launcher for this purpose. For example, on a development machine you can run `sudo -v` in the same terminal and use a launcher containing `exec /usr/bin/sudo -n /absolute/path/radminlan-utun "$@"`.

After connecting the CLI, enter `tun utun70 /absolute/path/to/launcher`. Configure the interface from another terminal using the address shown by `status` and the intended peer:

```sh
sudo ifconfig utun70 inet <local-virtual-ip> <peer-virtual-ip> netmask 255.255.255.255 mtu 1500 up
```

The peer route and interface disappear when the helper closes. Add additional host routes only for peers that need system-level access.

## Windows system Wintun

Run `./platform/windows/build.ps1` with MSVC Build Tools available. The script verifies the published Wintun SDK archive and the Windows signature on its DLL. Keep `wintun.dll` beside the executable. Starting Wintun and assigning IPs need an administrator terminal.

Connect the CLI and run `tun RadminLAN /path/to/radminlan-wintun.exe`. Set the new adapter's IPv4 to the address shown by `status` and add individual peer routes through it. Run these commands in PowerShell:

```powershell
New-NetIPAddress -InterfaceAlias 'RadminLAN' -IPAddress '<local-virtual-ip>' -PrefixLength 32
New-NetRoute -InterfaceAlias 'RadminLAN' -DestinationPrefix '<peer-virtual-ip>/32' -NextHop '0.0.0.0'
```

Use `untun` to release the helper. The existing official Radmin adapter remains a separate backend in the Minecraft mod.

## Minecraft embedding

Build RadminLAN Core first, then build RadminLAN with `RADMIN_CORE_JAR` set to the absolute core JAR path. The mod's `AUTO` backend uses the core on non-Windows platforms and keeps the official named-pipe backend on Windows. `CORE` forces the core, and `WINDOWS` forces the official backend.

```powershell
$env:RADMIN_CORE_JAR='D:\工作区\radminlan-core\build\libs\radminlan-core-0.1.0.jar'
D:\工作区\workspace3\scripts\build-26.3.ps1 -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
```

The build verifies the JAR identity and copies only the core classes and its license into the game mod. The rest of the core JAR remains available for the standalone CLI distribution.

## Platform plan

- Windows uses the official-compatible service backend by default, with optional embedded core mode.
- Linux and macOS can use the embedded engine for game TCP. Both have optional CLI system packet interfaces.
- Android uses a separate `VpnService` companion with the same Radmin-compatible protocol and a device-local identity. See `android/README.md`.
- iOS has a Packet Tunnel app and extension project. Installing it on a device requires Apple signing and provisioning. See `ios/README.md`.
- Windows has a CLI Wintun system adapter. The embedded core can use process-level forwarding, and the existing service backend uses the official system adapter.

## Compatibility boundary

The OpenRad implementation is a community protocol implementation. It has local synthetic tests against its own protocol vectors and interoperability tests against a reference endpoint. Successful compilation or local tests do not prove every official deployment, relay policy or NAT shape. Windows installations can select the existing official-service backend independently of the embedded core.
