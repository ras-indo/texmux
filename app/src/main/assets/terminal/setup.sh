#!/system/bin/sh
set -e

# setup.sh - First-run rootfs configuration
# This runs inside the proot container — all paths are container paths
# (the ROOTFS directory is the root of the container).

# Source utility functions
if [ -f /apps/bin/utils ]; then
    . /apps/bin/utils
fi

info "Configuring TexMux container..."

# Write hostname
printf '%s\n' "texmux" > /etc/hostname

# Write resolv.conf
{
    printf '%s\n' "nameserver 8.8.8.8"
    printf '%s\n' "nameserver 8.8.4.4"
} > /etc/resolv.conf 2>/dev/null || true

# Write hosts
{
    printf '%s\n' "127.0.0.1   localhost.localdomain localhost"
    printf '%s\n' "::1         localhost.localdomain localhost ip6-localhost ip6-loopback"
} > /etc/hosts 2>/dev/null || true

# Add android-specific groups to /etc/group
GROUP_FILE="/etc/group"
if [ -f "$GROUP_FILE" ]; then
    for line in "inet:x:3003" "everybody:x:9997" "android_app:x:20455"; do
        gid="${line##*:}"
        if ! grep -q ":$gid$" "$GROUP_FILE" 2>/dev/null; then
            printf '%s\n' "$line" >> "$GROUP_FILE"
        fi
    done
fi

# Mark setup complete
touch /.texmux_setup_complete
info "TexMux container setup complete."
