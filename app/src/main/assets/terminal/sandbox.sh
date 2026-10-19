#!/system/bin/sh
# sandbox.sh — Enter PRoot container (re:Terminal compatible)
# No PREFIX directory — everything is under files/ directly.
FILES_DIR="/data/data/com.termux/files"
ROOTFS_DIR="${FILES_DIR}/ROOTFS"
APPS_BIN="${FILES_DIR}/apps/bin"
TMP_DIR="${FILES_DIR}/tmp"

# ── Linker ──────────────────────────────────────────────────────────
LINKER="/system/bin/linker64"
if [ ! -f "$LINKER" ]; then
  LINKER="/system/bin/linker"
fi

# ── Android shell fallback ───────────────────────────────────────────
# Kalau proot gagal start (unknown option / binary rusak / rootfs tak
# lengkap), JANGAN matiin session — drop ke shell Android host yang
# sudah disiapkan di apps/bin/android, atau mksh host sebagai cadangan.
android_fallback() {
  /system/bin/echo ""
  /system/bin/echo "=== PRoot launch failed — dropping to Android host shell ==="
  /system/bin/echo "=== (fix the container, then run: /data/data/com.termux/files/apps/bin/sandbox) ==="
  if [ -x "$APPS_BIN/android" ]; then
    exec "$APPS_BIN/android"
  fi
  # Cadangan terakhir: mksh host langsung
  exec /system/bin/sh -i
}

# ── Proot binary ────────────────────────────────────────────────────
PROOT="$APPS_BIN/proot"
if [ ! -f "$PROOT" ]; then
  /system/bin/echo "FATAL: proot not found at $PROOT"
  android_fallback
fi

# ── PROOT_LOADER — resolve once, cache result ──────────────────────
# The native lib dir follows a predictable pattern. Cache it so we
# don't glob every session — that 'ls -d /data/app/com.termux*/...'
# can be slow (1-2 glob resolves) on some kernels.
PROOT_LOADER_CACHE="${TMP_DIR}/.proot_loader_cache"
if [ -f "$PROOT_LOADER_CACHE" ]; then
  . "$PROOT_LOADER_CACHE"
else
  NATIVE_LIB_DIR=$(/system/bin/ls -d /data/app/com.termux*/lib/arm64* 2>/dev/null | /system/bin/head -1)
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
  $LINKER "$PROOT" --link2symlink /system/bin/tar -xf "$FILES_DIR/rootfs.tar.gz" -C "$ROOTFS_DIR"
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
ARGS="$ARGS --link2symlink"
ARGS="$ARGS -L"

# ── Launch ──────────────────────────────────────────────────────────
export PATH=/system/bin:/system/xbin:/bin:/sbin:/usr/bin:/usr/sbin

INIT_EXEC="$APPS_BIN/init"
if [ ! -r "$INIT_EXEC" ]; then
  INIT_EXEC="$APPS_BIN/init.sh"
fi

if [ ! -r "$INIT_EXEC" ]; then
  /system/bin/echo "ERROR: Could not find init or init.sh in $APPS_BIN"
  android_fallback
fi

# ── Pre-flight: validate proot + args + rootfs sebelum commit ──────
# Cek cepat (argumen valid, binary OK, rootfs ada /bin/true). Kalau
# gagal → android_fallback, jangan biarkan session mati tanpa output.
if ! $LINKER "$PROOT" $ARGS /bin/true >/dev/null 2>&1; then
  /system/bin/echo "Pre-flight check failed for: $LINKER $PROOT (args or rootfs problem)"
  android_fallback
fi

exec $LINKER "$PROOT" $ARGS /bin/sh "$INIT_EXEC" "$@"
