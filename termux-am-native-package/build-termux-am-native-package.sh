#!/bin/bash
# Build termux-am-native-package standalone executables using Android NDK CMake toolchain.
#
# Usage:
#   ./build-termux-am-native-package.sh <ndk-dir> <output-dir> [target-abi]
#
#   ndk-dir     : Path to Android NDK (e.g. $ANDROID_HOME/ndk/29.0.14206865)
#   output-dir  : Where to copy built binaries + processed scripts
#   target-abi  : One of: arm64-v8a, armeabi-v7a, x86_64, x86 (default: arm64-v8a)
#
# Pure C build — no C++ runtime, no patchelf needed.

set -euo pipefail

NDK_DIR="$1"
OUTPUT_DIR="$2"
TARGET_ABI="${3:-arm64-v8a}"

if [ -z "$NDK_DIR" ] || [ -z "$OUTPUT_DIR" ]; then
    echo "Usage: $0 <ndk-dir> <output-dir> [target-abi]" 1>&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build/${TARGET_ABI}"

TOOLCHAIN_FILE="${NDK_DIR}/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN_FILE" ]; then
    echo "NDK CMake toolchain not found at: $TOOLCHAIN_FILE" 1>&2
    exit 1
fi

mkdir -p "$BUILD_DIR"

cmake -S "$SCRIPT_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$TARGET_ABI" \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_INSTALL_PREFIX="${OUTPUT_DIR}" \
    -DCMAKE_BUILD_TYPE=Release

cmake --build "$BUILD_DIR" --target install -- -j$(nproc)

# Pure C — no libc++_shared.so to strip.

# Create symlinks and flatten for asset deployment
ln -sf termux-am-socket "${OUTPUT_DIR}/bin/am-socket"
ln -sf termux-am "${OUTPUT_DIR}/bin/am"
if [ -f "${OUTPUT_DIR}/bin/termux-am-socket" ]; then
    cp "${OUTPUT_DIR}/bin/termux-am-socket" "${OUTPUT_DIR}/termux-am-socket"
    cp "${OUTPUT_DIR}/bin/termux-am" "${OUTPUT_DIR}/termux-am"
    cp -a "${OUTPUT_DIR}/bin/am" "${OUTPUT_DIR}/am"
    cp -a "${OUTPUT_DIR}/bin/am-socket" "${OUTPUT_DIR}/am-socket"
fi

echo ""
echo "✓ Build complete. Output: ${OUTPUT_DIR}"
echo "  bin/  ← termux-am-socket (pure C, no C++ runtime), termux-am, am, am-socket"
