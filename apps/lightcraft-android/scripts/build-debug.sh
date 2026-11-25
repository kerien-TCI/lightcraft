#!/usr/bin/env bash
# Build a private, locally signed APK. Never publishes or installs it.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
ndk=${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}
abi=${1:-arm64-v8a}
case $abi in
    arm64-v8a) target=aarch64-linux-android ;;
    x86_64) target=x86_64-linux-android ;;
    *) echo "Usage: $0 [arm64-v8a|x86_64]" >&2; exit 2 ;;
esac
case $(uname -s) in
    Linux) host=linux-x86_64 ;;
    Darwin) host=darwin-x86_64 ;;
    *) echo 'Run this script in a Linux or macOS Android SDK environment.' >&2; exit 2 ;;
esac
bin=$ndk/toolchains/llvm/prebuilt/$host/bin
clang=$bin/${target}31-clang
[[ -x $clang ]] || { echo "Missing NDK compiler: $clang" >&2; exit 1; }
var_target=${target//-/_}
upper_target=$(printf "%s" "$var_target" | tr '[:lower:]' '[:upper:]')
export "CC_$var_target=$clang" "CXX_$var_target=$clang++" "AR_$var_target=$bin/llvm-ar"
export "CARGO_TARGET_${upper_target}_LINKER=$clang"
export CARGO_TARGET_DIR=${CARGO_TARGET_DIR:-"$PWD/target"}
export CARGO_PROFILE_DEV_DEBUG=${CARGO_PROFILE_DEV_DEBUG:-0}
cargo build --locked -p lightcraft-android --lib --target "$target"
dest=apps/lightcraft-android/jniLibs/$abi
mkdir -p "$dest"
cp "$CARGO_TARGET_DIR/$target/debug/libmain.so" "$dest/"
(cd apps/lightcraft-android/android && ./gradlew :app:assembleDebug "-PrequestedAbi=$abi")
printf '%s\n' "$PWD/apps/lightcraft-android/android/app/build/outputs/apk/debug/app-debug.apk"
