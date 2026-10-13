#!/system/bin/sh
# Fix build environment for termux-app on arm64 without working NDK
# Run this before `./gradlew assembleDebug`

set -e

# 1. Setup QEMU amd64 sysroot for AAPT2 (x86_64 binary needs emulation)
if [ ! -f /tmp/amd64-sysroot/lib64/ld-linux-x86-64.so.2 ]; then
  mkdir -p /tmp/amd64-sysroot/lib64
  cp -a /usr/x86_64-linux-gnu/lib/*.so* /tmp/amd64-sysroot/lib/
  cp -a /usr/x86_64-linux-gnu/lib/ld-linux-x86-64.so.2 /tmp/amd64-sysroot/lib64/
  echo "  ✅ amd64-sysroot created"
else
  echo "  ✅ amd64-sysroot exists"
fi

# 2. Override AAPT2 to use wrapper with QEMU
export ANDROID_AAPT2_OVERRIDE=/opt/android-sdk/build-tools/36.1.0/aapt2
echo "  ✅ AAPT2 override set"

# 3. Clean and build
exec ./gradlew clean assembleDebug "$@"
