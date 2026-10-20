#!/bin/sh

TERMUX_API_PREFIX="/data/data/com.termux/files/apps/com.termux"
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:${TERMUX_API_PREFIX}/bin
export HOME=/root
export USER=root
export SHELL=/bin/bash
export TERM="${TERM:-xterm-256color}"
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# Write resolv.conf if missing
if [ ! -s /etc/resolv.conf ]; then
    echo "nameserver 8.8.8.8" > /etc/resolv.conf
fi

# Inject Android supplementary group IDs — batched, no fork per gid.
# Single awk pass: reads $GROUPS (set by su), writes /etc/group in one shot.
# This avoids spawning id+grep for every single group (~10-30 forks total).
if ! grep -qs "^android_" /etc/group 2>/dev/null; then
    for gid in $(id -G 2>/dev/null); do
        echo "android_$gid:x:$gid:" >> /etc/group
    done
fi

# Source profile if exists
if [ -f /etc/profile ]; then
    . /etc/profile
fi
if [ -f ~/.profile ]; then
    . ~/.profile
fi

# ── `ps` and file ownership under the fake root ────────────────────
# proot reports geteuid()==0 but /proc/<pid>/status keeps the real app uid.
# procps' default selection for a bare `ps` is "same euid + same tty", so it
# compares 0 against the real uid, matches nothing, and prints only its header.
# Two small repairs, both idempotent and outside the rootfs tarball:
#   1. a `ps` wrapper that, with no arguments, selects the caller's session
#      (that is what people mean by "which processes are running here")
#   2. a passwd entry for the real uid, so `ps aux` and `ls -l` show a name
#      instead of a bare number for files we own
_RU=$(awk '/^Uid:/{print $2; exit}' /proc/self/status 2>/dev/null)
_RG=$(awk '/^Gid:/{print $2; exit}' /proc/self/status 2>/dev/null)

if [ -n "$_RU" ] && [ "$_RU" != "0" ] && [ -w /etc/passwd ] \
   && ! awk -F: -v u="$_RU" '$3 == u { found = 1 } END { exit !found }' /etc/passwd; then
    echo "root:x:$_RU:${_RG:-$_RU}:root:/root:/bin/bash" >> /etc/passwd
fi

_PS_WRAPPER="/usr/local/bin/ps"
if ! grep -qs "select by session" "$_PS_WRAPPER" 2>/dev/null; then
    mkdir -p /usr/local/bin
    cat > "$_PS_WRAPPER" << 'PS_WRAPPER_EOF'
#!/bin/sh
# Bare `ps`: select by session.  The default selection cannot work here
# because proot fakes geteuid() while /proc reports the real uid.
if [ $# -eq 0 ]; then
    _sid=$(/bin/ps -o sess= -p $$ 2>/dev/null | tr -d ' \n')
    [ -n "$_sid" ] && exec /bin/ps -s "$_sid"
fi
exec /bin/ps "$@"
PS_WRAPPER_EOF
    chmod 755 "$_PS_WRAPPER"
fi

# If arguments passed, exec them (non-interactive command)
if [ $# -gt 0 ]; then
    exec "$@"
fi

# ── termux.properties — check dulu sebelum deploy ──────────────────
TERMUX_DOTDIR="/data/data/com.termux/files/.termux"
mkdir -p "$TERMUX_DOTDIR"

# Only write termux.properties if neither target nor source asset exists
if [ ! -f "$TERMUX_DOTDIR/termux.properties" ]; then
    TERMUX_PROPS_ASSET="/data/data/com.termux/files/apps/bin/termux.properties"
    if [ -f "$TERMUX_PROPS_ASSET" ]; then
        cp "$TERMUX_PROPS_ASSET" "$TERMUX_DOTDIR/termux.properties"
    else
        cat > "$TERMUX_DOTDIR/termux.properties" << 'PROPERTIES_EOF'
# Extra keys row
extra-keys = [['ESC','/','-','HOME','UP','END','PGUP'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]
use-black-ui = true
cursor-style = block
cursor-blink = true
terminal-margin-horizontal = 4
allow-external-apps = true
PROPERTIES_EOF
    fi
fi

# Symlink .termux — hanya recreate kalo beda target
_CURRENT_TERMUX_LINK=$(readlink /root/.termux 2>/dev/null)
if [ "$_CURRENT_TERMUX_LINK" != "$TERMUX_DOTDIR" ]; then
    rm -f /root/.termux
    ln -s "$TERMUX_DOTDIR" /root/.termux
fi

# ── am command — coba app_process dulu, fallback ke socket ─────────
# Original termux pake app_process + am.apk. Di proot app_process bisa
# jalan kalo /system bind-mounted. Kalo gagal (crash/segfault), otomatis
# fallback ke termux-am-socket (AF_UNIX ke AmSocketServer di app process).
_AM_SCRIPT="/usr/local/bin/am"
_AM_APK_PATH="/data/data/com.termux/files/apps/com.termux/libexec/am.apk"
_AM_SOCKET_BIN="/data/data/com.termux/files/apps/com.termux/bin/termux-am-socket"
if [ ! -f "$_AM_SCRIPT" ] || ! grep -qs "app_process\|termux-am-socket" "$_AM_SCRIPT" 2>/dev/null; then
    cat > "$_AM_SCRIPT" << 'AM_SCRIPT'
#!/system/bin/sh
# am — kaya termux original + fallback socket untuk proot.
# Priority: app_process > socket (termux-am-socket).
TERMUX_AM_VERSION=0.8.0
AM_APK_PATH="/data/data/com.termux/files/apps/com.termux/libexec/am.apk"
AM_SOCKET_BIN="/data/data/com.termux/files/apps/com.termux/bin/termux-am-socket"

if [ "$1" = "--version" ]; then
    echo "$TERMUX_AM_VERSION"
    exit 0
fi

# 1. Coba app_process dulu (kaya termux original di host)
#    app_process butuh /system/framework/*.jar (boot classpath).
#    Kalo gak ada (proot tanpa bind mount), bakal crash/segfault.
if [ -f "$AM_APK_PATH" ] && [ -x "/system/bin/app_process" ]; then
    if [ -w "$AM_APK_PATH" ]; then
        user="$(id -u)"
        if [ "$user" != "0" ] && [ "$user" != "1000" ] && [ "$user" != "2000" ]; then
            chmod 0400 "$AM_APK_PATH" 2>/dev/null
        fi
    fi
    export CLASSPATH="$AM_APK_PATH"
    unset LD_LIBRARY_PATH LD_PRELOAD
    /system/bin/app_process -Xnoimage-dex2oat / com.termux.am.Am "$@" 2>/dev/null && exit 0
    # fall through to socket fallback
fi

# 2. Fallback: socket-based am via termux-am-socket
exec "$AM_SOCKET_BIN" "$(printf "%q " "$@")"
AM_SCRIPT
    chmod +x "$_AM_SCRIPT"
fi

# Symlink: termux-am -> am (user bisa milih mana yang mana)
_AM_SYMLINK="/usr/local/bin/termux-am"
_CURRENT_AM_LINK=$(readlink "$_AM_SYMLINK" 2>/dev/null)
if [ "$_CURRENT_AM_LINK" != "/usr/local/bin/am" ]; then
    ln -sf /usr/local/bin/am "$_AM_SYMLINK"
fi

# Also create 'am' at TERMUX_API_PREFIX/bin/
mkdir -p "${TERMUX_API_PREFIX}/bin"
_AM2_SYMLINK="${TERMUX_API_PREFIX}/bin/am"
_CURRENT_AM2_LINK=$(readlink "$_AM2_SYMLINK" 2>/dev/null)
if [ "$_CURRENT_AM2_LINK" != "/usr/local/bin/am" ]; then
    ln -sf /usr/local/bin/am "$_AM2_SYMLINK"
fi

# termux-reload-settings — hanya tulis kalo belum ada
_AM_RELOAD="/usr/local/bin/termux-reload-settings"
if [ ! -f "$_AM_RELOAD" ]; then
    cat > "$_AM_RELOAD" << 'RELOAD_SCRIPT'
#!/bin/sh

if [ "$#" != "0" ]; then
        echo 'usage: termux-reload-settings'
        echo 'Use without arguments to reload settings after modifying any of:'
        echo '  ~/.termux/colors.properties'
        echo '  ~/.termux/font.ttf '
        echo '  ~/.termux/termux.properties'
        exit
fi

am broadcast --user 0 -a "com.termux.app.reload_style" "com.termux" > /dev/null
RELOAD_SCRIPT
    chmod +x "$_AM_RELOAD"
fi

# ── Termux:API scripts — symlink ke /usr/local/bin/ ────────────────
# Only link missing ones — skip existing to avoid redundant stat() calls.
for _tapi_script in "${TERMUX_API_PREFIX}/bin/termux-"*; do
    [ -f "$_tapi_script" ] || continue
    _tapi_name="${_tapi_script##*/}"
    [ -e "/usr/local/bin/${_tapi_name}" ] && continue
    ln -sf "$_tapi_script" "/usr/local/bin/${_tapi_name}"
done

# termux-api → client terkompilasi.  Link lama bisa menunjuk ke
# libexec/termux-api-broadcast (build lama: tanpa --timeout/--help/exit code),
# jadi yang dicek target link-nya, bukan cuma keberadaan path.
_TAPI_CLIENT="${TERMUX_API_PREFIX}/bin/termux-api"
if [ -f "$_TAPI_CLIENT" ]; then
    _tapi_current=$(readlink "/usr/local/bin/termux-api" 2>/dev/null)
    if [ "$_tapi_current" != "$_TAPI_CLIENT" ]; then
        ln -sf "$_TAPI_CLIENT" "/usr/local/bin/termux-api"
    fi
fi

# ---- 'android' command — cek dulu baru tulis ──────────────────────
_ANDROID_BIN="/usr/local/bin/android"
if [ ! -f "$_ANDROID_BIN" ]; then
    ANDROID_ASSET="/data/data/com.termux/files/apps/bin/android"
    if [ -f "$ANDROID_ASSET" ]; then
        cp "$ANDROID_ASSET" "$_ANDROID_BIN"
        chmod +x "$_ANDROID_BIN"
    else
        cat > "$_ANDROID_BIN" << 'ANDROID_SCRIPT'
#!/bin/sh
export PATH=/system/bin:/system/xbin
export HOME=/data/data/com.termux
echo "Android host shell (Ctrl+D to return to container)"
printf '%s\n' 'PS1="$ "' 'cd /data/data/com.termux' > /tmp/.androidrc
ENV=/tmp/.androidrc exec /system/bin/sh -i
ANDROID_SCRIPT
        chmod +x "$_ANDROID_BIN"
    fi
fi

export PROMPT_COMMAND='builtin printf "\e]0;${USER}@${HOSTNAME%%.*}: $(pwd)\a\e]7;file://localhost$(pwd)\a"'

# Interactive shell — cd to HOME only if no cwd was inherited
if [ "$(pwd)" = "/" ]; then
    cd "$HOME"
fi
exec /bin/bash -i
