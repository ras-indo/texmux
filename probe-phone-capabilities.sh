#!/system/bin/sh
# Probe capabilities HP Android — jalanin INI di dalam texmux di HP, kirim hasilnya.
echo "=== ARCH ==="; uname -m
echo "=== KERNEL ==="; uname -r
echo "=== ANDROID SDK ==="; getprop ro.build.version.sdk 2>/dev/null
echo "=== MODEL / BRAND ==="; getprop ro.product.model 2>/dev/null; getprop ro.product.brand 2>/dev/null
echo "=== IS PIXEL? ==="; getprop ro.product.manufacturer 2>/dev/null | grep -qi google && echo "PIXEL (AVF/pKVM mungkin)" || echo "non-Pixel"
echo "=== RAM (MB) ==="; grep MemTotal /proc/meminfo
echo ""
echo "=== [1] /dev/kvm ada? (butuh root utk akses) ==="
ls -la /dev/kvm 2>&1 || echo "TIDAK ADA /dev/kvm"
echo "=== [2] root? ==="
id 2>/dev/null
[ "$(id -u)" = "0" ] && echo "SUDAH ROOT" || echo "TIDAK root (uid=$(id -u))"
su -c id 2>/dev/null | head -1 && echo "su BISA dipakai" || echo "su gak jalan"
echo "=== [3] Magisk? ==="
ls /data/adb/magisk 2>/dev/null && echo "MAGISK ADA" || echo "gak ada magisk"
echo "=== [4] AVF / pKVM (Android Virtualization Framework) ==="
getprop | grep -iE "virtualization|vm\.|pkvm" 2>/dev/null | head -5 || echo "(no avf props)"
ls /dev/vsock 2>&1
echo "=== [5] USB device access (butuh root utk /dev/bus/usb) ==="
ls /dev/bus/usb 2>&1 | head -3
echo "=== [6] kernel namespace/cgroup (buat LXC/container) ==="
ls /proc/self/ns 2>/dev/null
[ -d /sys/fs/cgroup ] && echo "cgroup ADA: $(ls /sys/fs/cgroup 2>/dev/null | head -1)" || echo "cgroup gak jelas"
cat /proc/self/cgroup 2>/dev/null | head -3
echo ""
echo "=== KESIMPULAN MANUAL ==="
echo "Pixel 8+/root+/dev/kvm  -> VM KVM/pKVM (kernel bebas, near-native)"
echo "root + /dev/kvm         -> QEMU/KVM (kernel bebas, near-native)"
echo "root aja (no kvm)       -> LXC/chroot (service+USB, TAPI kernel Android)"
echo "nothing                 -> stuck proot / QEMU TCG lambat"
