#!/system/bin/sh
# sandbox.sh — Enter PRoot container (re:Terminal compatible)
# No PREFIX directory — everything is under files/ directly.
FILES_DIR="/data/data/texmux.app/files"
ROOTFS_DIR="${FILES_DIR}/ROOTFS"
APPS_BIN="${FILES_DIR}/apps/bin"
TMP_DIR="${FILES_DIR}/tmp"

# ── Linker ──────────────────────────────────────────────────────────
LINKER="/system/bin/linker64"
if [ ! -f "$LINKER" ]; then
  LINKER="/system/bin/linker"
fi

# ── Proot binary ────────────────────────────────────────────────────
PROOT="$APPS_BIN/proot"
if [ ! -f "$PROOT" ]; then
  /system/bin/echo "FATAL: proot not found at $PROOT"
  exit 1
fi

# ── Fallback proot ──────────────────────────────────────────────────
# The APK ships two binaries: "proot" (built from source, with --sysvipc, -L
# and the path cache) and "proot-legacy" (the binary Termux ships).  One 16 ms
# probe decides which one to use, so a bad source build can never leave the
# session without a shell to fall back to.
if [ -x "$APPS_BIN/proot-legacy" ] && [ -f "$ROOTFS_DIR/etc/passwd" ]; then
  if ! $LINKER "$PROOT" -r "$ROOTFS_DIR" /bin/true > /dev/null 2>&1; then
    /system/bin/echo "proot: primary build failed its pre-flight -- using proot-legacy"
    PROOT="$APPS_BIN/proot-legacy"
  fi
fi

# ── PROOT_LOADER — resolve once, cache result ──────────────────────
# The native lib dir follows a predictable pattern. Cache it so we
# don't glob every session — that 'ls -d /data/app/texmux.app*/...'
# can be slow (1-2 glob resolves) on some kernels.
PROOT_LOADER_CACHE="${TMP_DIR}/.proot_loader_cache"
if [ -f "$PROOT_LOADER_CACHE" ]; then
  . "$PROOT_LOADER_CACHE"
else
  NATIVE_LIB_DIR=$(/system/bin/ls -d /data/app/texmux.app*/lib/arm64* 2>/dev/null | /system/bin/head -1)
  if [ -n "$NATIVE_LIB_DIR" ]; then
    if [ -f "$NATIVE_LIB_DIR/libproot-loader.so" ]; then
      echo "export PROOT_LOADER='$NATIVE_LIB_DIR/libproot-loader.so'" > "$PROOT_LOADER_CACHE"
    fi
    if [ -f "$NATIVE_LIB_DIR/libproot-loader32.so" ]; then
      echo "export PROOT_LOADER_32='$NATIVE_LIB_DIR/libproot-loader32.so'" >> "$PROOT_LOADER_CACHE"
    fi
    # Also cache libtermux (used by JNI createSubprocess)
    if [ -f "$NATIVE_LIB_DIR/libtermux.so" ]; then
      echo "export TERMUX_LIB_DIR='$NATIVE_LIB_DIR'" >> "$PROOT_LOADER_CACHE"
    fi
  fi
  # If we wrote a cache, source it now
  if [ -f "$PROOT_LOADER_CACHE" ]; then
    . "$PROOT_LOADER_CACHE"
  fi
fi

# ── Temp dirs ───────────────────────────────────────────────────────
/system/bin/mkdir -p "$TMP_DIR"

# Pre-set by Java with session ID; fallback to PID
if [ -z "${PROOT_TMP_DIR-}" ]; then
  PROOT_SESSION_DIR="${TMP_DIR}/.proot-$$"
  /system/bin/mkdir -p "$PROOT_SESSION_DIR"
  export PROOT_TMP_DIR="$PROOT_SESSION_DIR"
else
  /system/bin/mkdir -p "$PROOT_TMP_DIR"
fi
export TMPDIR="$TMP_DIR"

# ── Fake /proc files — fallback kalo Android block akses ────────────
SYS_DATA_DIR="${FILES_DIR}/.sysdata"
/system/bin/mkdir -p "$SYS_DATA_DIR"

# Batch-create fake proc files: only write missing ones.
# Using shell I/O redirection is cheaper than fopen/fork per file.
for _sf in stat vmstat loadavg uptime version \
  sysctl_entry_cap_last_cap sysctl_inotify_max_user_watches \
  sysctl_kernel_overflowuid sysctl_kernel_overflowgid; do
  if [ ! -f "$SYS_DATA_DIR/$_sf" ]; then
    case "$_sf" in
      stat)   /system/bin/echo "cpu  0 0 0 0 0 0 0 0 0 0" > "$SYS_DATA_DIR/stat" ;;
      vmstat) /system/bin/echo "nr_free_pages 0" > "$SYS_DATA_DIR/vmstat" ;;
      loadavg) /system/bin/echo "0.00 0.00 0.00 1/1 1" > "$SYS_DATA_DIR/loadavg" ;;
      uptime) /system/bin/echo "0.00 0.00" > "$SYS_DATA_DIR/uptime" ;;
      version) /system/bin/echo "Linux version 5.10.0-android (builduser@host) (Android clang)" > "$SYS_DATA_DIR/version" ;;
      sysctl_entry_cap_last_cap) /system/bin/echo "39" > "$SYS_DATA_DIR/sysctl_entry_cap_last_cap" ;;
      sysctl_inotify_max_user_watches) /system/bin/echo "65536" > "$SYS_DATA_DIR/sysctl_inotify_max_user_watches" ;;
      sysctl_kernel_overflowuid) /system/bin/echo "65534" > "$SYS_DATA_DIR/sysctl_kernel_overflowuid" ;;
      sysctl_kernel_overflowgid) /system/bin/echo "65534" > "$SYS_DATA_DIR/sysctl_kernel_overflowgid" ;;
    esac
  fi
done

# Fake /proc/stat only carries the cpu line, and procps needs a btime line to
# turn a process' starttime into a clock time - without it every START column
# reads 1970.  uptime is faked as 0.00 above, so "booted now" is the value
# consistent with it.  Refreshed every session (one date call) so the cached
# file cannot go stale.
if [ -f "$SYS_DATA_DIR/stat" ]; then
  {
    /system/bin/echo "cpu  0 0 0 0 0 0 0 0 0 0"
    /system/bin/echo "btime $(/system/bin/date +%s)"
  } > "$SYS_DATA_DIR/stat" 2>/dev/null
fi

# ── PROOT_L2S_DIR — link2symlink backing files di dalam rootfs ─────
L2S_DIR="${ROOTFS_DIR}/.l2s"
export PROOT_L2S_DIR="$L2S_DIR"
/system/bin/mkdir -p "$L2S_DIR"

# ── Extract rootfs — fast check, no ls|grep pipe ───────────────────
# Instead of spawning ls | grep to check emptiness, just test for a
# single well-known file that always exists in an extracted rootfs.
# This avoids forking TWO subprocesses on every session start.
if [ ! -f "$ROOTFS_DIR/etc/passwd" ]; then
  # ROOTFS_DIR itself may be missing on first run
  /system/bin/mkdir -p "$ROOTFS_DIR"
  # No --link2symlink here: /data (ext4/f2fs) supports real hardlinks, and the
  # extension's .l2s.<name>NNNN replacement fails with ENOENT on modern kernels
  # (proven on both this tree and upstream proot 5.5.0).  Plain extraction keeps
  # the hardlinks as hardlinks: perl/perl5.38.2 and gunzip/uncompress share one
  # inode with nlink=2, and no .l2s.* junk is left behind.
  $LINKER "$PROOT" /system/bin/tar -xf "$FILES_DIR/rootfs.tar.gz" -C "$ROOTFS_DIR"
fi

# ── Temp dir inside rootfs ──────────────────────────────────────────
if [ ! -d "$ROOTFS_DIR/tmp" ]; then
  /system/bin/mkdir -p "$ROOTFS_DIR/tmp"
  /system/bin/chmod 1777 "$ROOTFS_DIR/tmp"
fi

# ── Build proot mounts ──────────────────────────────────────────────
ARGS="--kill-on-exit"

# CWD inheritance logic
CURRENT_PWD=$(pwd)
case "$CURRENT_PWD" in
  "$ROOTFS_DIR"*)
    CONTAINER_CWD="${CURRENT_PWD#$ROOTFS_DIR}"
    if [ -z "$CONTAINER_CWD" ]; then CONTAINER_CWD="/"; fi
    ARGS="$ARGS -w $CONTAINER_CWD" ;;
  *) ARGS="$ARGS -w /" ;;
esac

# Cache resolved mount paths — most are stable across sessions.
# Writing resolved paths here avoids re-running realpath on every mount.
MOUNT_CACHE="${TMP_DIR}/.mount_cache"
if [ -f "$MOUNT_CACHE" ] && [ -r "$MOUNT_CACHE" ]; then
  ARGS="$ARGS $(/system/bin/cat "$MOUNT_CACHE")"
else
  _cache=""
  for system_mnt in /apex /odm /product /system /system_ext /vendor \
    /linkerconfig/ld.config.txt \
    /linkerconfig/com.android.art/ld.config.txt \
    /plat_property_contexts /property_contexts; do
    if [ -e "$system_mnt" ]; then
      mnt=$(/system/bin/realpath "$system_mnt" 2>/dev/null || /system/bin/echo "$system_mnt")
      _cache="$_cache -b ${mnt}"
    fi
  done
  # Standard binds — always present
  _cache="$_cache -b /sdcard -b /storage -b /dev -b /data -b /dev/urandom:/dev/random -b /proc"
  echo "$_cache" > "$MOUNT_CACHE"
  ARGS="$ARGS $_cache"
fi

# Bind fake /proc files — skip fork-heavy _fb(), use shell built-in test.
# `[ -r "$1" ]` is a shell built-in — NO subprocess fork needed.
for _fb_proc in /proc/stat /proc/vmstat /proc/loadavg /proc/uptime /proc/version \
  /proc/sys/kernel/cap_last_cap /proc/sys/fs/inotify/max_user_watches \
  /proc/sys/kernel/overflowuid /proc/sys/kernel/overflowgid; do
  _fb_file="${_fb_proc#/proc/}"
  _fb_file="${_fb_file//\//_}"
  if ! [ -r "$_fb_proc" ] 2>/dev/null; then
    # create dummy file if missing so proot doesn't warn
    [ -f "$SYS_DATA_DIR/$_fb_file" ] || echo "0" > "$SYS_DATA_DIR/$_fb_file" 2>/dev/null
    ARGS="$ARGS -b $SYS_DATA_DIR/$_fb_file:$_fb_proc"
  fi
done

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi
if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi
if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi
if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

ARGS="$ARGS -b $ROOTFS_DIR/tmp:/dev/shm"
ARGS="$ARGS -b /sys"
ARGS="$ARGS -r $ROOTFS_DIR"
ARGS="$ARGS -0"
# --link2symlink is deliberately NOT passed for the session: the extension
# filters link/rename/symlink syscalls and costs ~10-14% on every workload
# (du -s /usr 901ms with it vs 773ms without, 120 guest forks 713ms vs 641ms),
# while /data supports real hardlinks.  Re-add it only for a rootfs stored on a
# filesystem without hardlink support.
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# ── Launch ──────────────────────────────────────────────────────────
export PATH=/system/bin:/system/xbin:/bin:/sbin:/usr/bin:/usr/sbin

INIT_EXEC="$APPS_BIN/init"
if [ ! -r "$INIT_EXEC" ]; then
  INIT_EXEC="$APPS_BIN/init.sh"
fi

if [ ! -r "$INIT_EXEC" ]; then
  /system/bin/echo "ERROR: Could not find init or init.sh in $APPS_BIN"
  exit 1
fi

exec $LINKER "$PROOT" $ARGS /bin/sh "$INIT_EXEC" "$@"
