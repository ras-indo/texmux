# Changelog

## 2026-07-07 — Build successful + API alignment

### Build fixes
+ Added `--configure-on-demand` flag for faster Gradle cold start on QEMU
+ Upgraded Java compatibility from 9 to 11 (`VERSION_11`) for AGP 8.13
+ Disabled corrupted NDK 27 and NDK 29 directories (only `.installer` present)
+ Added `org.gradle.daemon=true` for faster incremental builds
+ Reduced `org.gradle.jvmargs` to `-Xmx1024M` for QEMU compatibility

### Build output
+ APK generated: `texmux-app_apt-android-7-debug_universal.apk` (51MB)
