#!/usr/bin/make -f
# Makefile for cross-compiling PRoot for Android using the Android NDK.
#
# PRoot is a user-space chroot and bind-mount implementation using ptrace.
# It relies on an embedded ELF trampoline ("loader") to intercept syscalls,
# and on the talloc memory pool library from the Samba project.
#
# This Makefile:
#   1. Downloads PRoot source (git clone) and talloc source (from Samba)
#   2. Builds talloc as a static library using the NDK cross-compiler
#   3. Builds the PRoot loader trampoline (freestanding, no libc)
#   4. Builds the main PRoot binary, statically linked with talloc,
#      dynamically linked to Bionic (Android libc)
#   5. Copies the result to the Termux assets directory
#
# Usage:
#   make proot-arm64        # Build for arm64 (primary target)
#   make proot-arm          # Build for arm32
#   make all                # Build both
#   make clean              # Remove build artifacts
#   make install            # Copy to Termux assets dir
#
# Prerequisites:
#   - Android NDK r29+ installed (set NDK_DIR before running)
#   - Linux build machine (x86_64 host)
#   - git, make, wget, tar

SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c

# ------------------------------------------------------------------
# Configuration - override these on the command line or via env vars
# ------------------------------------------------------------------

# Path to Android NDK (e.g. /opt/android-ndk or $ANDROID_HOME/ndk/29.0.14206865)
NDK_DIR ?= $(ANDROID_HOME)/ndk/29.0.14206865

# Minimum API level (Android 7.0+)
API_LEVEL ?= 24

# Output directory for the built binary (Termux assets)
ASSETS_DIR ?= $(abspath ../app/src/main/assets/proot)

# Build directory (all intermediate artifacts)
BUILD_DIR ?= $(abspath build)

# Source directories
PROOT_SRC_DIR := $(BUILD_DIR)/proot-src
TALLOC_SRC_DIR := $(BUILD_DIR)/talloc-src

# ------------------------------------------------------------------
# Toolchain paths (derived from NDK_DIR)
# ------------------------------------------------------------------

# NDK toolchain definitions
define arm64_toolchain
  TOOLCHAIN_arm64 := $(BUILD_DIR)/toolchain-arm64
  TOOLCHAIN_TARGET_arm64 := aarch64-linux-android
  TOOLCHAIN_CC_arm64 := $$(TOOLCHAIN_arm64)/bin/$$(TOOLCHAIN_TARGET_arm64)$(API_LEVEL)-clang
  TOOLCHAIN_BIN_arm64 := $$(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/bin
  TOOLCHAIN_AR_arm64 := $$(TOOLCHAIN_BIN_arm64)/llvm-ar
  TOOLCHAIN_STRIP_arm64 := $$(TOOLCHAIN_BIN_arm64)/llvm-strip
  TOOLCHAIN_OBJCOPY_arm64 := $$(TOOLCHAIN_BIN_arm64)/llvm-objcopy
  TOOLCHAIN_OBJDUMP_arm64 := $$(TOOLCHAIN_BIN_arm64)/llvm-objdump
  LOADER_ADDRESS_arm64 := 0x2000000000
  LOADER_ARCH_arm64 := ARCH_ARM64
  LINKER_arm64 := /system/bin/linker64
  BINARY_arm64 := proot
endef

define arm_toolchain
  TOOLCHAIN_arm := $(BUILD_DIR)/toolchain-arm
  TOOLCHAIN_TARGET_arm := armv7a-linux-androideabi
  TOOLCHAIN_CC_arm := $$(TOOLCHAIN_arm)/bin/$$(TOOLCHAIN_TARGET_arm)$(API_LEVEL)-clang
  TOOLCHAIN_BIN_arm := $$(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/bin
  TOOLCHAIN_AR_arm := $$(TOOLCHAIN_BIN_arm)/llvm-ar
  TOOLCHAIN_STRIP_arm := $$(TOOLCHAIN_BIN_arm)/llvm-strip
  TOOLCHAIN_OBJCOPY_arm := $$(TOOLCHAIN_BIN_arm)/llvm-objcopy
  TOOLCHAIN_OBJDUMP_arm := $$(TOOLCHAIN_BIN_arm)/llvm-objdump
  LOADER_ADDRESS_arm := 0x10000000
  LOADER_ARCH_arm := ARCH_ARM_EABI
  LINKER_arm := /system/bin/linker
  BINARY_arm := proot-arm
endef

$(eval $(arm64_toolchain))
$(eval $(arm_toolchain))

# ------------------------------------------------------------------
# Sources & objects (shared across architectures)
# ------------------------------------------------------------------

# PRoot source files (relative to PROOT_SRC_DIR/src/)
PROOT_SRCS := \
	cli/cli.c \
	cli/proot.c \
	cli/note.c \
	execve/enter.c \
	execve/exit.c \
	execve/shebang.c \
	execve/elf.c \
	execve/ldso.c \
	execve/auxv.c \
	execve/aoxp.c \
	path/binding.c \
	path/glue.c \
	path/canon.c \
	path/path.c \
	path/proc.c \
	path/temp.c \
	syscall/seccomp.c \
	syscall/syscall.c \
	syscall/chain.c \
	syscall/enter.c \
	syscall/exit.c \
	syscall/sysnum.c \
	syscall/socket.c \
	syscall/heap.c \
	syscall/rlimit.c \
	tracee/tracee.c \
	tracee/mem.c \
	tracee/reg.c \
	tracee/event.c \
	ptrace/ptrace.c \
	ptrace/user.c \
	ptrace/wait.c \
	extension/extension.c \
	extension/kompat/kompat.c \
	extension/fake_id0/fake_id0.c \
	extension/link2symlink/link2symlink.c \
	extension/portmap/portmap.c \
	extension/portmap/map.c

PROOT_OBJS := $(PROOT_SRCS:%.c=%.o)

# ------------------------------------------------------------------
# Phony targets
# ------------------------------------------------------------------

.PHONY: all clean proot proot-arm64 proot-arm install \
	download-proot download-talloc \
	setup-toolchain-arm64 setup-toolchain-arm

.DEFAULT_GOAL := proot-arm64

all: proot-arm64 proot-arm

proot: proot-arm64

# ------------------------------------------------------------------
# 1. Download sources
# ------------------------------------------------------------------

download-proot:
	@echo "==> Downloading PRoot source"
	@rm -rf $(PROOT_SRC_DIR)
	@mkdir -p $(BUILD_DIR)
	git clone --depth=1 https://github.com/proot-me/proot.git $(PROOT_SRC_DIR)

TALLOC_VERSION := 2.4.2
TALLOC_TARBALL := $(BUILD_DIR)/talloc-$(TALLOC_VERSION).tar.gz
TALLOC_URL := https://download.samba.org/pub/talloc/talloc-$(TALLOC_VERSION).tar.gz

download-talloc:
	@echo "==> Downloading talloc $(TALLOC_VERSION) (standalone tarball, includes lib/replace)"
	@mkdir -p $(BUILD_DIR)
	@if [ ! -f $(TALLOC_TARBALL) ]; then \
		(command -v curl >/dev/null && curl -sSL -o $(TALLOC_TARBALL) $(TALLOC_URL)) || \
		wget -q -O $(TALLOC_TARBALL) $(TALLOC_URL); \
	fi
	@rm -rf $(TALLOC_SRC_DIR)
	@tar xzf $(TALLOC_TARBALL) -C $(BUILD_DIR)
	@mv $(BUILD_DIR)/talloc-$(TALLOC_VERSION) $(TALLOC_SRC_DIR)

# ------------------------------------------------------------------
# 2. NDK standalone toolchain
# ------------------------------------------------------------------

setup-toolchain-arm64:
	@echo "==> Setting up arm64 NDK toolchain"
	@mkdir -p $(BUILD_DIR)
	$(NDK_DIR)/build/tools/make_standalone_toolchain.py \
		--arch arm64 \
		--api $(API_LEVEL) \
		--install-dir $(TOOLCHAIN_arm64) \
		--force 2>/dev/null || \
	(echo "WARNING: make_standalone_toolchain.py not available (NDK r25+)."; \
	 echo "  Using NDK's direct toolchain paths instead."; \
	 mkdir -p $(TOOLCHAIN_arm64)/bin; \
	 for tool in cc ar strip objcopy objdump; do \
	   ln -sf $(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/bin/$(TOOLCHAIN_TARGET_arm64)-$$tool \
	     $(TOOLCHAIN_arm64)/bin/ 2>/dev/null || true; \
	 done; \
	 for tool in $(TOOLCHAIN_TARGET_arm64)$(API_LEVEL)-clang $(TOOLCHAIN_TARGET_arm64)-clang; do \
	   ln -sf $(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/bin/$$tool \
	     $(TOOLCHAIN_arm64)/bin/ 2>/dev/null || true; \
	 done; \
	 ln -sf $(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
	   $(TOOLCHAIN_arm64)/sysroot 2>/dev/null || true)

setup-toolchain-arm:
	@echo "==> Setting up arm NDK toolchain"
	@mkdir -p $(BUILD_DIR)
	$(NDK_DIR)/build/tools/make_standalone_toolchain.py \
		--arch arm \
		--api $(API_LEVEL) \
		--install-dir $(TOOLCHAIN_arm) \
		--force 2>/dev/null || \
	(echo "WARNING: make_standalone_toolchain.py not available (NDK r25+)."; \
	 echo "  Using NDK's direct toolchain paths instead."; \
	 mkdir -p $(TOOLCHAIN_arm)/bin; \
	 for tool in cc ar strip objcopy objdump; do \
	   ln -sf $(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/bin/$(TOOLCHAIN_TARGET_arm)-$$tool \
	     $(TOOLCHAIN_arm)/bin/ 2>/dev/null || true; \
	 done; \
	 for tool in $(TOOLCHAIN_TARGET_arm)$(API_LEVEL)-clang $(TOOLCHAIN_TARGET_arm)-clang; do \
	   ln -sf $(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/bin/$$tool \
	     $(TOOLCHAIN_arm)/bin/ 2>/dev/null || true; \
	 done; \
	 ln -sf $(NDK_DIR)/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
	   $(TOOLCHAIN_arm)/sysroot 2>/dev/null || true)

# ------------------------------------------------------------------
# 3. Build talloc static library
# ------------------------------------------------------------------
# Talloc is a hierarchical memory pool library from the Samba project.
# PRoot uses it for all internal memory management. We build it as a
# static library so it gets linked directly into the proot binary.

TALLOC_SRC := $(TALLOC_SRC_DIR)/talloc.c
TALLOC_CFGFLAGS := --disable-python
TALLOC_COMPILE_DEFS := -D_GNU_SOURCE=1 -D_XOPEN_SOURCE_EXTENDED=1 -fPIC \
	-D__STDC_WANT_LIB_EXT1__=1 \
	-DSTATIC_talloc_MODULES=NULL \
	'-DSTATIC_talloc_MODULES_PROTO=extern void __talloc_dummy_module_proto(void)'

# Generate config.h via NATIVE (host) waf configure. waf's runtime checks
# (uname, simple C program, lib/replace execute=True probes) can't run arm64
# binaries on the x86_64 CI host, and cross-answers would be fragile. talloc's
# config.h is LP64-arch-agnostic (SIZEOF_LONG/VOID_P/SIZE_T=8 identical for
# x86_64 and arm64) and its dangerous-guard headers (bsd/string, setproctitle,
# unix.h, sys/atomic) are absent, so a host config.h is Bionic-safe for arm64.
$(BUILD_DIR)/talloc-arm64/.configured: download-talloc
	@echo "==> Configuring talloc (host-native) via waf to generate config.h"
	@cd $(TALLOC_SRC_DIR) && CC=gcc CFLAGS="-fPIC -O2" \
		./configure $(TALLOC_CFGFLAGS) >$(BUILD_DIR)/talloc-configure.log 2>&1 \
		|| { echo "!! talloc configure failed; log tail:"; tail -40 $(BUILD_DIR)/talloc-configure.log; exit 1; }
	@test -f $(TALLOC_SRC_DIR)/bin/default/config.h
	@echo "==> Sanitizing host config.h for Bionic/NDK target"
	@sed -i \
		-e '/^#define uint_t /d' \
		-e '/^#define offset_t /d' \
		-e '/^#[[:space:]]*define[[:space:]]*HAVE_[A-Z_]*CRYPT/d' \
		$(TALLOC_SRC_DIR)/bin/default/config.h
	@mkdir -p $(dir $@) && touch $@

# Compile talloc.c for the TARGET using the host-generated config.h + the exact
# waf recipe (from compile_commands.json). Run from bin/default so the relative
# -I paths (config.h, lib/replace) resolve.
$(BUILD_DIR)/talloc-arm64/talloc.o: $(BUILD_DIR)/talloc-arm64/.configured setup-toolchain-arm64
	@echo "==> Compiling talloc.c (arm64, NDK clang)"
	@mkdir -p $(dir $@)
	@cd $(TALLOC_SRC_DIR)/bin/default && \
		$(TOOLCHAIN_CC_arm64) $(TALLOC_COMPILE_DEFS) \
		-I. -I../.. -Ilib/replace -I../../lib/replace \
		../../talloc.c -c -o $@

$(BUILD_DIR)/talloc-arm64/libtalloc.a: $(BUILD_DIR)/talloc-arm64/talloc.o
	@mkdir -p $(dir $@)
	$(TOOLCHAIN_AR_arm64) rcs $@ $<

talloc-arm64: $(BUILD_DIR)/talloc-arm64/libtalloc.a

# arm (32-bit): LP32, so host LP64 config.h SIZEOF values would be wrong. The CI
# only builds arm64 (install -> proot-arm64); keep arm target compiling via its
# own native configure is not possible (host is LP64). Leave arm wired to the
# same config.h but note it is not CI-covered.
$(BUILD_DIR)/talloc-arm/talloc.o: $(BUILD_DIR)/talloc-arm64/.configured setup-toolchain-arm
	@echo "==> Compiling talloc.c (arm, NDK clang; shares host config.h)"
	@mkdir -p $(dir $@)
	@cd $(TALLOC_SRC_DIR)/bin/default && \
		$(TOOLCHAIN_CC_arm) $(TALLOC_COMPILE_DEFS) \
		-I. -I../.. -Ilib/replace -I../../lib/replace \
		../../talloc.c -c -o $@

$(BUILD_DIR)/talloc-arm/libtalloc.a: $(BUILD_DIR)/talloc-arm/talloc.o
	@mkdir -p $(dir $@)
	$(TOOLCHAIN_AR_arm) rcs $@ $<

talloc-arm: $(BUILD_DIR)/talloc-arm/libtalloc.a

# ------------------------------------------------------------------
# 4. Proot build definitions (per-architecture)
# ------------------------------------------------------------------

# Common CPP flags: enable large file support, GNU_SOURCE, include paths
PROOT_CPPFLAGS := \
	-D_FILE_OFFSET_BITS=64 \
	-D_GNU_SOURCE \
	-I$(PROOT_SRC_DIR)/src

# Architecture-specific flags
PROOT_CFLAGS_arm64 := \
	-target aarch64-linux-android$(API_LEVEL) \
	-g -O2 -Wall -Wextra \
	-fPIE \
	-I$(BUILD_DIR)/talloc-arm64/include \
	-I$(TOOLCHAIN_arm64)/sysroot/usr/include

PROOT_CFLAGS_arm := \
	-target armv7a-linux-androideabi$(API_LEVEL) \
	-g -O2 -Wall -Wextra \
	-fPIE \
	-I$(BUILD_DIR)/talloc-arm/include \
	-I$(TOOLCHAIN_arm)/sysroot/usr/include

PROOT_LDFLAGS_arm64 := \
	-fPIE -pie \
	-Wl,-z,noexecstack \
	-L$(BUILD_DIR)/talloc-arm64 \
	-L$(TOOLCHAIN_arm64)/sysroot/usr/lib/$(TOOLCHAIN_TARGET_arm64)/$(API_LEVEL) \
	-ltalloc

PROOT_LDFLAGS_arm := \
	-fPIE -pie \
	-Wl,-z,noexecstack \
	-L$(BUILD_DIR)/talloc-arm \
	-L$(TOOLCHAIN_arm)/sysroot/usr/lib/$(TOOLCHAIN_TARGET_arm)/$(API_LEVEL) \
	-ltalloc

# ------------------------------------------------------------------
# 5. Build proot loader (freestanding ELF trampoline)
# ------------------------------------------------------------------
# The loader is a freestanding (no libc) ELF binary that PRoot extracts
# at runtime and uses as the ELF interpreter for tracees. It is embedded
# into the final proot binary via objcopy.

LOADER_CFLAGS := -fPIC -ffreestanding -I$(PROOT_SRC_DIR)/src
LOADER_LDFLAGS := -static -nostdlib -Wl,-z,noexecstack

LOADER_OBJECTS := loader/loader.o loader/assembly.o

define build_loader_template
# $1 = architecture suffix (arm64 or arm)
# $2 = LOADER_ADDRESS
# $3 = ARCH define
# $4 = CC
# $5 = OBJCOPY
# $6 = OBJDUMP
# $7 = STRIP
# $8 = OBJECTS list variable suffix

.PHONY: loader-$1

loader-$1: $$(BUILD_DIR)/loader-$1/loader.elf

# Build loader objects
PROOT_SRC_$1 := $(PROOT_SRC_DIR)/src

$$(BUILD_DIR)/loader-$1/loader.o: $$(PROOT_SRC_$1)/loader/loader.c | download-proot
	@mkdir -p $$(dir $$@)
	$$(TOOLCHAIN_CC_$1) $$(LOADER_CFLAGS) -D$3 -DLOADER_ADDRESS=$2 \
		-c $$< -o $$@

$$(BUILD_DIR)/loader-$1/assembly.o: $$(PROOT_SRC_$1)/loader/assembly.S | download-proot
	@mkdir -p $$(dir $$@)
	$$(TOOLCHAIN_CC_$1) $$(LOADER_CFLAGS) -D$3 -DLOADER_ADDRESS=$2 \
		-c $$< -o $$@

# Link loader as freestanding ELF
$$(BUILD_DIR)/loader-$1/loader: $$(BUILD_DIR)/loader-$1/loader.o $$(BUILD_DIR)/loader-$1/assembly.o
	@mkdir -p $$(dir $$@)
	$$(TOOLCHAIN_CC_$1) -o $$@ $$^ $$(LOADER_LDFLAGS) -Wl,-Ttext=$2

# Strip and prepare for embedding
$$(BUILD_DIR)/loader-$1/loader.stripped: $$(BUILD_DIR)/loader-$1/loader
	$$(TOOLCHAIN_STRIP_$1) -o $$@ $$<

$$(BUILD_DIR)/loader-$1/loader.elf: $$(BUILD_DIR)/loader-$1/loader.stripped
	cp $$< $$@

# objcopy to wrap loader as a .o file for linking
$$(BUILD_DIR)/proot-$1/loader-wrapped.o: $$(BUILD_DIR)/loader-$1/loader.elf
	@mkdir -p $$(dir $$@)
	$$(TOOLCHAIN_OBJCOPY_$1) \
		--input-target binary \
		--output-target $$(shell $$(TOOLCHAIN_OBJDUMP_$1) -f $$(BUILD_DIR)/proot-$1/cli/cli.o | grep 'file format' | awk '{print $$$$4}') \
		--binary-architecture $$(shell $$(TOOLCHAIN_OBJDUMP_$1) -f $$(BUILD_DIR)/proot-$1/cli/cli.o | grep architecture | cut -f 1 -d , | awk '{print $$$$2}') \
		$$< $$@

endef

$(eval $(call build_loader_template,arm64,$(LOADER_ADDRESS_arm64),$(LOADER_ARCH_arm64),\
	$(TOOLCHAIN_CC_arm64),$(TOOLCHAIN_OBJCOPY_arm64),$(TOOLCHAIN_OBJDUMP_arm64),$(TOOLCHAIN_STRIP_arm64)))

$(eval $(call build_loader_template,arm,$(LOADER_ADDRESS_arm),$(LOADER_ARCH_arm),\
	$(TOOLCHAIN_CC_arm),$(TOOLCHAIN_OBJCOPY_arm),$(TOOLCHAIN_OBJDUMP_arm),$(TOOLCHAIN_STRIP_arm)))

# ------------------------------------------------------------------
# 6. Build PRoot main binary
# ------------------------------------------------------------------

# Pattern rule: compile each proot source file
# We need a pattern rule per architecture

define compile_proot_template
# $1 = architecture suffix (arm64 or arm)
# $2 = CC
# $3 = CFLAGS
# $4 = PROOT_SRC_DIR
# $5 = CPPFLAGS

$$(BUILD_DIR)/proot-$1/%.o: $$(PROOT_SRC_DIR)/src/%.c | download-proot
	@mkdir -p $$(dir $$@)
	$$(TOOLCHAIN_CC_$1) $$(PROOT_CFLAGS_$1) $$(PROOT_CPPFLAGS) -MD -c $$< -o $$@

endef

$(eval $(call compile_proot_template,arm64))
$(eval $(call compile_proot_template,arm))

# Generate build.h for version info
define generate_build_h_template
# $1 = architecture suffix
# $2 = PROOT_SRC_DIR

$$(BUILD_DIR)/proot-$1/build.h:
	@mkdir -p $$(dir $$@)
	@echo "/* Auto-generated */" > $$@
	@echo "#ifndef BUILD_H" >> $$@
	@echo "#define BUILD_H" >> $$@
	@echo "#define VERSION \"5.4.0-android\"" >> $$@
	@echo "#define HAVE_SECCOMP_FILTER" >> $$@
	@echo "#define HAVE_PROCESS_VM" >> $$@
	@echo "#endif /* BUILD_H */" >> $$@

# Determine which objects need build.h
$$(BUILD_DIR)/proot-$1/cli/proot.o: $$(BUILD_DIR)/proot-$1/build.h
$$(BUILD_DIR)/proot-$1/cli/cli.o: $$(BUILD_DIR)/proot-$1/build.h

endef

$(eval $(call generate_build_h_template,arm64))
$(eval $(call generate_build_h_template,arm))

# Talloc include forwarding (so #include <talloc.h> works with our static build)
$(BUILD_DIR)/talloc-arm64/include/talloc.h: $(BUILD_DIR)/talloc-arm64/.configured
	@mkdir -p $(dir $@)
	cp $(TALLOC_SRC_DIR)/talloc.h $@

$(BUILD_DIR)/talloc-arm/include/talloc.h: $(BUILD_DIR)/talloc-arm64/.configured
	@mkdir -p $(dir $@)
	cp $(TALLOC_SRC_DIR)/talloc.h $@

# Build the final proot binary
proot-arm64: \
		download-proot download-talloc \
		setup-toolchain-arm64 \
		talloc-arm64 \
		$(BUILD_DIR)/talloc-arm64/include/talloc.h \
		$(addprefix $(BUILD_DIR)/proot-arm64/,$(PROOT_OBJS)) \
		$(BUILD_DIR)/proot-arm64/loader-wrapped.o
	@echo "==> Linking proot (arm64)"
	@mkdir -p $(BUILD_DIR)/proot-arm64
	$(TOOLCHAIN_CC_arm64) \
		$(PROOT_CFLAGS_arm64) \
		$(PROOT_CPPFLAGS) \
		-o $(BUILD_DIR)/proot-arm64/proot \
		$(addprefix $(BUILD_DIR)/proot-arm64/,$(PROOT_OBJS)) \
		$(BUILD_DIR)/proot-arm64/loader-wrapped.o \
		$(PROOT_LDFLAGS_arm64)
	@echo "==> Checking ELF interpreter"
	-readelf -l $(BUILD_DIR)/proot-arm64/proot 2>/dev/null | grep -q 'interpreter' && \
	  echo "  NOTE: binary requests interpreter:" && \
	  readelf -l $(BUILD_DIR)/proot-arm64/proot 2>/dev/null | grep 'interpreter' || \
	  echo "  OK: no interpreter (static binary)"
	@echo "==> Build complete: $(BUILD_DIR)/proot-arm64/proot"
	@file $(BUILD_DIR)/proot-arm64/proot

proot-arm: \
		download-proot download-talloc \
		setup-toolchain-arm \
		talloc-arm \
		$(BUILD_DIR)/talloc-arm/include/talloc.h \
		$(addprefix $(BUILD_DIR)/proot-arm/,$(PROOT_OBJS)) \
		$(BUILD_DIR)/proot-arm/loader-wrapped.o
	@echo "==> Linking proot (arm)"
	@mkdir -p $(BUILD_DIR)/proot-arm
	$(TOOLCHAIN_CC_arm) \
		$(PROOT_CFLAGS_arm) \
		$(PROOT_CPPFLAGS) \
		-o $(BUILD_DIR)/proot-arm/proot \
		$(addprefix $(BUILD_DIR)/proot-arm/,$(PROOT_OBJS)) \
		$(BUILD_DIR)/proot-arm/loader-wrapped.o \
		$(PROOT_LDFLAGS_arm)
	@echo "==> Build complete: $(BUILD_DIR)/proot-arm/proot"
	@file $(BUILD_DIR)/proot-arm/proot

# ------------------------------------------------------------------
# 7. Install
# ------------------------------------------------------------------

install: proot-arm64
	@echo "==> Installing to $(ASSETS_DIR)"
	@mkdir -p $(ASSETS_DIR)
	cp $(BUILD_DIR)/proot-arm64/proot $(ASSETS_DIR)/proot
	@echo "  -> $(ASSETS_DIR)/proot"
	@file $(ASSETS_DIR)/proot
	@echo ""
	@echo "To verify the binary works on an Android device:"
	@echo "  adb push $(ASSETS_DIR)/proot /data/local/tmp/"
	@echo "  adb shell /data/local/tmp/proot --version"

install-all: proot-arm64 proot-arm
	@echo "==> Installing arm64 binary"
	@mkdir -p $(ASSETS_DIR)
	cp $(BUILD_DIR)/proot-arm64/proot $(ASSETS_DIR)/proot
	@echo "  -> $(ASSETS_DIR)/proot"
	@echo "==> Installing arm binary"
	cp $(BUILD_DIR)/proot-arm/proot $(ASSETS_DIR)/proot-arm
	@echo "  -> $(ASSETS_DIR)/proot-arm"
	@file $(ASSETS_DIR)/proot $(ASSETS_DIR)/proot-arm

# ------------------------------------------------------------------
# 8. Clean
# ------------------------------------------------------------------

clean:
	rm -rf $(BUILD_DIR)

distclean: clean
	rm -rf $(PROOT_SRC_DIR) $(TALLOC_SRC_DIR)

# ------------------------------------------------------------------
# 9. Help
# ------------------------------------------------------------------

help:
	@echo "PRoot Android Cross-Compilation Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  proot-arm64    Build proot for arm64 (primary target)"
	@echo "  proot-arm      Build proot for arm32"
	@echo "  all            Build both architectures"
	@echo "  install        Build arm64 and copy to $(ASSETS_DIR)"
	@echo "  install-all    Build both and copy to assets"
	@echo "  clean          Remove build artifacts"
	@echo "  distclean      Remove build artifacts and downloaded sources"
	@echo ""
	@echo "Variables:"
	@echo "  NDK_DIR=$(NDK_DIR)"
	@echo "  API_LEVEL=$(API_LEVEL)"
	@echo "  ASSETS_DIR=$(ASSETS_DIR)"
	@echo "  BUILD_DIR=$(BUILD_DIR)"
	@echo ""
	@echo "Quick start:"
	@echo "  export ANDROID_HOME=/path/to/android-sdk"
	@echo "  cd termux-proot && make proot-arm64"
