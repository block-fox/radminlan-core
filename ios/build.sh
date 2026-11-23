#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
platform="${1:-simulator}"
if [[ "$platform" == "device" ]]; then
  target=aarch64-apple-ios
  sdk=iphoneos
else
  case "$(uname -m)" in
    arm64) target=aarch64-apple-ios-sim ;;
    x86_64) target=x86_64-apple-ios ;;
    *) echo 'Unsupported simulator host architecture' >&2; exit 2 ;;
  esac
  sdk=iphonesimulator
fi
rustup target add "$target"
SDKROOT="$(xcrun --sdk "$sdk" --show-sdk-path)" IPHONEOS_DEPLOYMENT_TARGET=16.0 \
  cargo build --manifest-path native/Cargo.toml --target "$target" --release --locked
mkdir -p native/build
cp "native/target/$target/release/libradminlan_apple.a" native/build/
xcodegen generate
if [[ "$platform" == "device" ]]; then
  xcodebuild -project RadminLAN.xcodeproj -scheme RadminLAN -sdk "$sdk" -configuration Release "${@:2}"
else
  xcodebuild -project RadminLAN.xcodeproj -scheme RadminLAN -sdk "$sdk" -configuration Debug \
    -derivedDataPath build CODE_SIGNING_ALLOWED=NO ONLY_ACTIVE_ARCH=YES "ARCHS=$(uname -m)"
fi
