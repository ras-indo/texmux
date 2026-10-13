#!/bin/bash
# NDK cross-compilation environment for Android ARM64
export NDK=/opt/android-sdk/ndk/29.0.14206865
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export TARGET=aarch64-linux-android
export API=21

export AR=$TOOLCHAIN/bin/llvm-ar
export AS=$TOOLCHAIN/bin/$TARGET-as
export CC=$TOOLCHAIN/bin/$TARGET$API-clang
export CXX=$TOOLCHAIN/bin/$TARGET$API-clang++
export LD=$TOOLCHAIN/bin/ld.lld
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip
export NM=$TOOLCHAIN/bin/llvm-nm
export OBJCOPY=$TOOLCHAIN/bin/llvm-objcopy
export OBJDUMP=$TOOLCHAIN/bin/llvm-objdump

export CFLAGS="-O2 -fPIC -DANDROID -D__ANDROID_API__=$API"
export LDFLAGS="-fPIC -Wl,-z,noexecstack"
export SYSROOT=$TOOLCHAIN/sysroot
export STAGING=/home/termux/staging
export PKG_CONFIG_PATH=$STAGING/lib/pkgconfig
export PKG_CONFIG_LIBDIR=$STAGING/lib/pkgconfig
export CROSS_COMPILE=$TARGET-

mkdir -p $STAGING
