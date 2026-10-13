# Building PRoot for Termux on Android

This document explains how to cross-compile [PRoot](https://github.com/proot-me/proot) for
Android using the Android NDK. PRoot lets you run a Linux distribution inside Termux using
`chroot` and bind mounts, all without root access.

## Quick Start

```bash
# 1. Set your NDK path
export ANDROID_HOME=/path/to/android-sdk

# 2. Build for arm64 (the primary target)
cd termux-proot/
make proot-arm64

# 3. Install to Termux assets
make install
```

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| **Build machine** | Linux x86_64 | Required for NDK cross-compilation |
| **Android NDK** | r29+ | Download from [developer.android.com/ndk](https://developer.android.com/ndk/downloads) |
| **git** | any | To clone PRoot and talloc sources |
| **make** | any | Build automation |
| **readelf** | any | From binutils, for final binary verification |

### NDK Installation

```bash
# Download NDK r29 (or newer)
wget https://dl.google.com/android/repository/android-ndk-r29-linux.zip
unzip android-ndk-r29-linux.zip

# Set the path
export ANDROID_HOME=/opt/android-ndk-r29
```

If you use Android Studio, the NDK is typically at:
- `$ANDROID_HOME/ndk/29.0.14206865/` (exact version may vary)
- `~/Android/Sdk/ndk/29.0.14206865/`

## Architecture Support

| Architecture | Make Target | Status | Linker |
|---|---|---|---|
| **arm64-v8a (64-bit ARM)** | `proot-arm64` | Primary target | `/system/bin/linker64` |
| **armeabi-v7a (32-bit ARM)** | `proot-arm` | Optional | `/system/bin/linker` |
| **x86_64, x86** | N/A | Not currently supported | N/A |

PRoot on Android is primarily used on 64-bit ARM devices. 32-bit ARM support is maintained
for compatibility but less tested.

## Build Process (Step by Step)

### What the Makefile Does

1. **Clones PRoot source** from `https://github.com/proot-me/proot.git`
2. **Clones talloc source** from the Samba project (`talloc-2.4.2`)
3. **Sets up NDK toolchains** using `make_standalone_toolchain.py` or direct symlinks
4. **Builds talloc as a static library** using the NDK cross-compiler
5. **Builds the PRoot loader** -- a freestanding ELF trampoline used for syscall interception
6. **Wraps the loader** into the proot binary using `objcopy`
7. **Compiles all PRoot source files** with Android NDK
8. **Links the final proot binary**, with talloc statically linked and Bionic (Android's libc)
   dynamically linked
9. **Copies the result** to `app/src/main/assets/proot/proot`

### How Talloc is Handled

PRoot uses the [talloc](https://talloc.samba.org/) hierarchical memory pool library for all
internal memory management. Standard Linux builds get talloc via `pkg-config` (from
`libtalloc-dev`). For Android cross-compilation:

- The Makefile downloads talloc source (from the Samba git repository)
- Compiles `talloc.c` with the NDK cross-compiler
- Packages it as a static library (`libtalloc.a`)
- Links it directly into the proot binary with `-ltalloc`

This avoids any dependency on a shared talloc library being present on the Android device.

### How the Loader Works

PRoot intercepts syscalls using a technique called ptrace-based syscall tracing. For each
traced program, PRoot uses a small ELF binary called the "loader" as the ELF interpreter.
This loader:

- Is built as a **freestanding binary** (no libc, `-nostdlib`)
- Runs at a fixed address specified in `arch.h` (e.g. `0x2000000000` for arm64)
- Is **embedded into the PRoot binary** itself via `objcopy --input-target binary`
- Gets **extracted to a temp file** at runtime by `extract_loader()` in `execve/enter.c`
- Can be overridden with the `PROOT_LOADER` or `PROOT_LOADER_32` environment variables

On Android, the key insight is that Linux desktop distributions use `/lib64/ld-linux-x86-64.so.2`
as the ELF interpreter, while Android uses `/system/bin/linker64` (arm64) or
`/system/bin/linker` (arm32). Proot's loader is separate from this -- it acts as a trampoline
to intercept syscalls, and the Android linker is still used to actually load shared libraries
for the traced program.

## Testing the Built Binary

### On a Linux Host (Limited)

PRoot is primarily an x86_64 Linux tool. The Android build will not run on a Linux host
because it's linked against Bionic (Android's libc) instead of glibc. You can verify the
binary structure:

```bash
# Check the ELF header and architecture
file build/proot-arm64/proot

# Verify it's linked against the correct Android linker
readelf -l build/proot-arm64/proot | grep interpreter

# Check it's a position-independent executable (PIE)
readelf -h build/proot-arm64/proot | grep Type

# List dynamic libraries
readelf -d build/proot-arm64/proot | grep NEEDED

# Confirm talloc is statically linked (it won't appear in NEEDED)
```

Expected output:
```
build/proot-arm64/proot: ELF shared object, 64-bit LSB arm64, dynamic (/system/bin/linker64), for Android 24, built by NDK r29, not stripped
```

### On an Android Device

```bash
# Push the binary to a test device
adb push build/proot-arm64/proot /data/local/tmp/

# Make it executable
adb shell chmod +x /data/local/tmp/proot

# Verify it runs
adb shell /data/local/tmp/proot --version

# Quick smoke test (runs a command in a minimal chroot)
adb shell /data/local/tmp/proot -r /data/local/tmp/rootfs /bin/sh -c 'echo hello'
```

You can also test directly in Termux:

```bash
# Copy the built binary to Termux's proot assets
cp build/proot-arm64/proot /path/to/termux-app/app/src/main/assets/proot/

# Or after building with the default ASSETS_DIR:
make install
# Then rebuild the Termux APK and install it
```

## Troubleshooting

### `make_standalone_toolchain.py` Not Found

NDK r25+ deprecated `make_standalone_toolchain.py`. The Makefile falls back to creating
direct symlinks to the NDK's LLVM toolchain. If the fallback fails:

```bash
# Check the NDK's actual toolchain path
ls $NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/
```

Then manually run the build with explicit paths:

```bash
make proot-arm64 \
  TOOLCHAIN_CC_arm64="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
```

### Build Fails with `talloc.h: No such file or directory`

The talloc download may have failed. Check:

```bash
ls build/talloc-src/talloc.h     # Should exist
ls build/talloc-arm64/include/   # Should have talloc.h
```

If missing, re-download:

```bash
rm -rf build/talloc-src
make download-talloc
```

### Linker Errors: `undefined reference to talloc_*`

The talloc static library wasn't linked. Verify:

```bash
# Check libtalloc.a exists and contains symbols
ar t build/talloc-arm64/libtalloc.a
nm build/talloc-arm64/libtalloc.a | grep talloc
```

If empty, rebuild talloc:

```bash
rm -rf build/talloc-arm64
make talloc-arm64
```

### Readelf Not Found

Install binutils:

```bash
# Debian/Ubuntu
apt-get install binutils

# Fedora
dnf install binutils

# Arch Linux
pacman -S binutils
```

### Binary Has Wrong ELF Interpreter

If `readelf -l` shows something other than `/system/bin/linker64`, the binary was linked
against the host system's linker. This means the NDK toolchain wasn't used correctly. Ensure:

1. `NDK_DIR` points to a valid NDK installation
2. The NDK's clang is being used (check `TOOLCHAIN_CC_arm64`)
3. No host system libraries leak into the link step

### Build Fails with `unknown target triple`

NDK r29 uses LLVM/clang. If you see target triple errors:

```bash
# Verify the NDK version supports your target
ls $NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/ | grep aarch64
```

The NDK should include `aarch64-linux-android24-clang` and related tools.

## Files

```
termux-proot/
├── Makefile                 # Cross-compilation build script
├── BUILD.md                 # This file
└── patches/
    └── proot-android.patch  # Reference patch for Android compatibility
```

## References

- [PRoot home page](https://proot-me.github.io/)
- [PRoot source code](https://github.com/proot-me/proot)
- [Talloc library](https://talloc.samba.org/)
- [Android NDK](https://developer.android.com/ndk)
- [Termux app source](https://github.com/termux/termux-app)
