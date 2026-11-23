# RadminLAN Core

This private repository contains the cross-platform Radmin-compatible VPN core used by RadminLAN when an embedded core artifact is supplied. The default user entry point is the `radminlan-core` CLI. The Minecraft mod consumes the same `CoreSession` and `TcpForwarder` APIs through an optional core JAR.

## Current scope

- Java compatibility layer sourced from OpenRad with MIT attribution retained in `vendor/openrad/LICENSE`.
- Persistent identity, authenticated coordinator session, public catalog, public/private network join, create, leave, delete, enable and disable operations.
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

The default profile is `.radminlan/core` under the current user’s home directory. `--profile` selects an isolated profile. A coordinator can be configured with `coordinator.properties` and `modulus.bin` in that profile. The CLI uses the official-compatible coordinator by default only after the user explicitly starts `connect`.

## Minecraft embedding

Build RadminLAN Core first, then build RadminLAN with `RADMIN_CORE_JAR` set to the absolute core JAR path. The mod's `AUTO` backend uses the core on non-Windows platforms and keeps the official named-pipe backend on Windows. `CORE` forces the core, and `WINDOWS` forces the official backend.

```powershell
$env:RADMIN_CORE_JAR='D:\工作区\radminlan-core\build\libs\radminlan-core-0.1.0.jar'
D:\工作区\workspace3\scripts\build-26.3.ps1 -JavaHome 'C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot'
```

The build verifies the JAR identity and copies only the core classes and its license into the game mod. The rest of the core JAR remains available for the standalone CLI distribution.

## Platform plan

- Windows uses the official-compatible service backend by default, with optional embedded core mode.
- Linux and macOS use the embedded in-process engine and loopback game forwarding.
- Android will use the same protocol and identity model through a `VpnService`/TUN adapter. This repository does not yet ship the Android adapter.
- Apple mobile support requires a Network Extension packet-tunnel target and entitlements. This repository does not yet ship that target.
- A native TUN/TAP adapter can be added later without changing the control, membership, or peer engine APIs.

## Compatibility boundary

The OpenRad implementation is a community protocol implementation. It has local synthetic tests against its own protocol vectors and interoperability tests against a reference endpoint. Successful compilation or local tests do not prove every official deployment, relay policy or NAT shape. Windows installations can select the existing official-service backend independently of the embedded core.
