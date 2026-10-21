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
TALLOC_SRC_DIR := $(abspath vendor/talloc)

# Which PRoot commit to build.
#
# Pinning matters: the APK ships whatever this builds, while the
# regression job tests whatever it clones.  If the two clone the branch
# tip independently, a push to the fork between the two clones makes
# the tested code and the shipped code different, and the gate then
# protects nothing.  PROOT_REF is a commit id, so the same value
# selects the same tree in both jobs.
PROOT_REPO ?= https://github.com/ras-indo/proot.git
PROOT_REF  ?= 9a06c4baecb2957dc5968f2085c597d582fb8086

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
	path/pcache.c \
	path/proc.c \
	path/temp.c \
	path/f2fs-bug.c \
	syscall/seccomp.c \
	syscall/syscall.c \
	syscall/chain.c \
	syscall/enter.c \
	syscall/exit.c \
	syscall/sysnum.c \
	syscall/socket.c \
	syscall/heap.c \
	syscall/rlimit.c \
	syscall/pipe_shadow.c \
	tracee/tracee.c \
	tracee/mem.c \
	tracee/reg.c \
	tracee/event.c \
	tracee/seccomp.c \
	tracee/statx.c \
	ptrace/ptrace.c \
	ptrace/user.c \
	ptrace/wait.c \
	extension/extension.c \
	extension/kompat/kompat.c \
	extension/ashmem_memfd/ashmem_memfd.c \
	extension/fix_symlink_size/fix_symlink_size.c \
	extension/hidden_files/hidden_files.c \
	extension/mountinfo/mountinfo.c \
	extension/port_switch/port_switch.c \
	extension/fake_id0/fake_id0.c \
	extension/fake_id0/access.c \
	extension/fake_id0/chmod.c \
	extension/fake_id0/chown.c \
	extension/fake_id0/chroot.c \
	extension/fake_id0/exec.c \
	extension/fake_id0/getsockopt.c \
	extension/fake_id0/helper_functions.c \
	extension/fake_id0/link.c \
	extension/fake_id0/mk.c \
	extension/fake_id0/open.c \
	extension/fake_id0/rename.c \
	extension/fake_id0/sendmsg.c \
	extension/fake_id0/socket.c \
	extension/fake_id0/stat.c \
	extension/fake_id0/symlink.c \
	extension/fake_id0/unlink.c \
	extension/fake_id0/utimensat.c \
	extension/link2symlink/link2symlink.c \
	extension/sysvipc/sysvipc.c \
	extension/sysvipc/sysvipc_msg.c \
	extension/sysvipc/sysvipc_sem.c \
	extension/sysvipc/sysvipc_shm.c

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
	@echo "==> Downloading PRoot source ($(PROOT_REF))"
	@rm -rf $(PROOT_SRC_DIR)
	@mkdir -p $(BUILD_DIR)
	@rm -rf $(PROOT_SRC_DIR)
	git init -q $(PROOT_SRC_DIR)
	cd $(PROOT_SRC_DIR) && git remote add origin $(PROOT_REPO)
	cd $(PROOT_SRC_DIR) && git fetch -q --depth=1 origin $(PROOT_REF)
	cd $(PROOT_SRC_DIR) && git checkout -q FETCH_HEAD
	@echo "==> PRoot checkout: $$(cd $(PROOT_SRC_DIR) && git rev-parse HEAD)"

TALLOC_VERSION := 2.4.2

# The talloc sources are vendored under vendor/talloc (talloc.c, talloc.h,
# the lib/replace headers and a pre-generated sanitized config.h).  The
# samba.org tarball proved unreliable from CI runners and waf's configure
# cannot run target binaries anyway, so the tree is committed instead.
download-talloc:
	@test -f $(TALLOC_SRC_DIR)/talloc.c || { \
		echo "!! missing vendored talloc sources in $(TALLOC_SRC_DIR)"; exit 1; }
	@echo "==> Using vendored talloc $(TALLOC_VERSION) ($(TALLOC_SRC_DIR))"

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

# The vendored tree ships a config.h generated once by waf on a 64-bit host
# with the Bionic-hostile defines (uint_t, offset_t, *_CRYPT) removed.  It is
# LP64-arch-agnostic, so it is reused as-is for arm64: no configure step.
$(BUILD_DIR)/talloc-arm64/talloc.o: $(TALLOC_SRC) setup-toolchain-arm64 download-talloc
	@echo "==> Compiling talloc.c (arm64, NDK clang)"
	@mkdir -p $(dir $@)
	$(TOOLCHAIN_CC_arm64) $(TALLOC_COMPILE_DEFS) \
		-I$(TALLOC_SRC_DIR) -I$(TALLOC_SRC_DIR)/lib/replace \
		$(TALLOC_SRC) -c -o $@

$(BUILD_DIR)/talloc-arm64/libtalloc.a: $(BUILD_DIR)/talloc-arm64/talloc.o
	@mkdir -p $(dir $@)
	$(TOOLCHAIN_AR_arm64) rcs $@ $<

talloc-arm64: $(BUILD_DIR)/talloc-arm64/libtalloc.a

# arm (32-bit): LP32, so host LP64 config.h SIZEOF values would be wrong. The CI
# only builds arm64 (install -> proot-arm64); keep arm target compiling via its
# own native configure is not possible (host is LP64). Leave arm wired to the
# same config.h but note it is not CI-covered.
$(BUILD_DIR)/talloc-arm/talloc.o: $(TALLOC_SRC) setup-toolchain-arm download-talloc
	@echo "==> Compiling talloc.c (arm, NDK clang; shares host config.h)"
	@mkdir -p $(dir $@)
	$(TOOLCHAIN_CC_arm) $(TALLOC_COMPILE_DEFS) \
		-I$(TALLOC_SRC_DIR) -I$(TALLOC_SRC_DIR)/lib/replace \
		$(TALLOC_SRC) -c -o $@

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
	-O3 -DNDEBUG -fomit-frame-pointer -ffunction-sections -fdata-sections -Wall -Wextra \
	-fPIE \
	-I$(BUILD_DIR)/talloc-arm64/include \
	-I$(TOOLCHAIN_arm64)/sysroot/usr/include

PROOT_CFLAGS_arm := \
	-target armv7a-linux-androideabi$(API_LEVEL) \
	-O3 -DNDEBUG -fomit-frame-pointer -ffunction-sections -fdata-sections -Wall -Wextra \
	-fPIE \
	-I$(BUILD_DIR)/talloc-arm/include \
	-I$(TOOLCHAIN_arm)/sysroot/usr/include

PROOT_LDFLAGS_arm64 := \
	-fPIE -pie \
	-Wl,-z,noexecstack -Wl,--gc-sections -Wl,-s \
	-L$(BUILD_DIR)/talloc-arm64 \
	-L$(TOOLCHAIN_arm64)/sysroot/usr/lib/$(TOOLCHAIN_TARGET_arm64)/$(API_LEVEL) \
	-ltalloc

PROOT_LDFLAGS_arm := \
	-fPIE -pie \
	-Wl,-z,noexecstack -Wl,--gc-sections -Wl,-s \
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

loader-$1: $$(BUILD_DIR)/loader-$1/loader.exe

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

# loader.c (termux/proot) references the embedded image as
# _binary_loader_exe_start/_end, so the objcopy input must be named
# loader.exe - the symbol names follow the input file name.
$$(BUILD_DIR)/loader-$1/loader.exe: $$(BUILD_DIR)/loader-$1/loader.stripped
	cp $$< $$@

# objcopy to wrap loader as a .o file for linking
$$(BUILD_DIR)/proot-$1/loader-wrapped.o: $$(BUILD_DIR)/loader-$1/loader.exe
	@mkdir -p $$(dir $$@)
	cd $$(BUILD_DIR)/loader-$1 && $$(TOOLCHAIN_OBJCOPY_$1) \
		--input-target binary \
		--output-target $$(shell $$(TOOLCHAIN_OBJDUMP_$1) -f $$(BUILD_DIR)/proot-$1/cli/cli.o | grep 'file format' | awk '{print $$$$4}') \
		--binary-architecture $$(shell $$(TOOLCHAIN_OBJDUMP_$1) -f $$(BUILD_DIR)/proot-$1/cli/cli.o | grep architecture | cut -f 1 -d , | awk '{print $$$$2}') \
		loader.exe $$(abspath $$@)

# loader-info.c: the pokedata offset the loader needs at run time.  The awk is
# the fork's portable one - mawk on Debian/Ubuntu has no strtonum().
$$(BUILD_DIR)/proot-$1/loader-info.c: $$(BUILD_DIR)/loader-$1/loader.exe
	@mkdir -p $$(dir $$@)
	readelf -s $$< | awk -f $$(PROOT_SRC_DIR)/src/loader/loader-info.awk > $$@

$$(BUILD_DIR)/proot-$1/loader-info.o: $$(BUILD_DIR)/proot-$1/loader-info.c
	$$(TOOLCHAIN_CC_$1) $$(PROOT_CFLAGS_$1) $$(PROOT_CPPFLAGS) -c $$< -o $$@

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
	$$(TOOLCHAIN_CC_$1) $$(PROOT_CFLAGS_$1) $$(PROOT_CPPFLAGS) -I$$(BUILD_DIR)/proot-$1 -MD -c $$< -o $$@

endef

$(eval $(call compile_proot_template,arm64))
$(eval $(call compile_proot_template,arm))

PROOT_VERSION ?= 5.5.0-android-pcache

# Generate build.h for version info
define generate_build_h_template
# $1 = architecture suffix
# $2 = PROOT_SRC_DIR

$$(BUILD_DIR)/proot-$1/build.h:
	@mkdir -p $$(dir $$@)
	@echo "/* Auto-generated */" > $$@
	@echo "#ifndef BUILD_H" >> $$@
	@echo "#define BUILD_H" >> $$@
	@echo "#define VERSION \"$(PROOT_VERSION)\"" >> $$@
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
$(BUILD_DIR)/talloc-arm64/include/talloc.h: $(TALLOC_SRC_DIR)/talloc.h
	@mkdir -p $(dir $@)
	cp $(TALLOC_SRC_DIR)/talloc.h $@

$(BUILD_DIR)/talloc-arm/include/talloc.h: $(TALLOC_SRC_DIR)/talloc.h
	@mkdir -p $(dir $@)
	cp $(TALLOC_SRC_DIR)/talloc.h $@

# Build the final proot binary
proot-arm64: \
		download-proot download-talloc \
		setup-toolchain-arm64 \
		talloc-arm64 \
		$(BUILD_DIR)/talloc-arm64/include/talloc.h \
		$(addprefix $(BUILD_DIR)/proot-arm64/,$(PROOT_OBJS)) \
		$(BUILD_DIR)/proot-arm64/loader-wrapped.o \
		$(BUILD_DIR)/proot-arm64/loader-info.o
	@echo "==> Linking proot (arm64)"
	@mkdir -p $(BUILD_DIR)/proot-arm64
	$(TOOLCHAIN_CC_arm64) \
		$(PROOT_CFLAGS_arm64) \
		$(PROOT_CPPFLAGS) \
		-o $(BUILD_DIR)/proot-arm64/proot \
		$(addprefix $(BUILD_DIR)/proot-arm64/,$(PROOT_OBJS)) \
		$(BUILD_DIR)/proot-arm64/loader-wrapped.o \
		$(BUILD_DIR)/proot-arm64/loader-info.o \
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
		$(BUILD_DIR)/proot-arm/loader-wrapped.o \
		$(BUILD_DIR)/proot-arm/loader-info.o
	@echo "==> Linking proot (arm)"
	@mkdir -p $(BUILD_DIR)/proot-arm
	$(TOOLCHAIN_CC_arm) \
		$(PROOT_CFLAGS_arm) \
		$(PROOT_CPPFLAGS) \
		-o $(BUILD_DIR)/proot-arm/proot \
		$(addprefix $(BUILD_DIR)/proot-arm/,$(PROOT_OBJS)) \
		$(BUILD_DIR)/proot-arm/loader-wrapped.o \
		$(BUILD_DIR)/proot-arm/loader-info.o \
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
