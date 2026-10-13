#!/bin/bash
# Build termux-api-native-package standalone executables using Android NDK CMake toolchain.
#
# Usage:
#   ./build-termux-api-native.sh <ndk-dir> <output-dir> [target-abi]
#
#   ndk-dir     : Path to Android NDK (e.g. $ANDROID_HOME/ndk/29.0.14206865)
#   output-dir  : Where to copy built binaries + processed scripts
#   target-abi  : One of: arm64-v8a, armeabi-v7a, x86_64, x86 (default: arm64-v8a)
#
# The script:
#   1. Runs CMake with the NDK toolchain to cross-compile termux-api-broadcast
#   2. Configures the .in scripts with TERMUX_PREFIX substitution
#   3. Copies outputs to the specified directory

set -euo pipefail

NDK_DIR="$1"
OUTPUT_DIR="$2"
TARGET_ABI="${3:-arm64-v8a}"

if [ -z "$NDK_DIR" ] || [ -z "$OUTPUT_DIR" ]; then
    echo "Usage: $0 <ndk-dir> <output-dir> [target-abi]"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build/${TARGET_ABI}"

# Map ABI to NDK target triple and API level
case "$TARGET_ABI" in
    arm64-v8a)
        TOOLCHAIN_TARGET="aarch64-linux-android"
        ;;
    armeabi-v7a)
        TOOLCHAIN_TARGET="armv7a-linux-androideabi"
        ;;
    x86_64)
        TOOLCHAIN_TARGET="x86_64-linux-android"
        ;;
    x86)
        TOOLCHAIN_TARGET="i686-linux-android"
        ;;
    *)
        echo "Unknown ABI: $TARGET_ABI"
        exit 1
        ;;
esac

TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN_FILE" ]; then
    echo "ERROR: NDK CMake toolchain not found at $TOOLCHAIN_FILE"
    exit 1
fi

# Create build directory
mkdir -p "$BUILD_DIR"

# Run CMake configure + build
cmake -S "$SCRIPT_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$TARGET_ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_INSTALL_PREFIX="${OUTPUT_DIR}" \
    -DCMAKE_BUILD_TYPE=Release

cmake --build "$BUILD_DIR" --target install -- -j$(nproc)

echo ""
echo "✓ Build complete. Output in: $OUTPUT_DIR"
echo "  bin/          ← termux-api (direct binary, no fork), termux-* scripts"
echo "  libexec/      ← termux-api-broadcast (backward compat), termux-callback"
echo "  lib/          ← libtermux-api.so, libtermux-api.a"
echo "  include/      ← termux-api.h"
