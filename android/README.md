# RadminLAN Android

This companion app provides a system-level Radmin VPN session through Android's `VpnService`. It uses the MIT-licensed OpenRad Android engine and interface from commit `d4314faea60fb88d632e133e971edb01a630e039`. The application ID and signing identity are separate from upstream OpenRad. The module retains the upstream MIT license in `LICENSE`. The SDK bootstrap script is from the same revision.

Use JDK 17 and Android SDK 34 to build `:app:testDebugUnitTest :app:assembleDebug`. Install the APK, open it, connect, and accept Android's VPN permission. The app handles network membership and routes VPN peer IPv4 packets through its TUN interface. It uses a device-local VPN identity stored by Android. Manage the Android session in the companion app. Game launchers that run Fabric and support the required desktop Java version can instead test the in-process mod backend under a separate profile.
