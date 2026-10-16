# MODUL APP — Aplikasi Utama Termux

## RINGKASAN

Modul Android app utama — semua activities, manajemen sesi terminal, service lifecycle, dan layar preferensi per-plugin. 36 file Java di `app/src/main/java/com/termux/app/`. Plugin Gradle `com.android.application`, namespace `com.termux`.

## STRUKTUR

```
app/
├── TermuxActivity.java          — Activity terminal utama
├── TermuxService.java           — Foreground service untuk sesi shell
├── TermuxApplication.java       — Inisialisasi app, crash handler
├── TermuxInstaller.java         — Ekstraksi & setup bootstrap
├── TermuxOpenReceiver.java      — Handler intent termux://open
├── RunCommandBroadcastReceiver.java  — Intent RUN_COMMAND masuk
├── RunCommandService.java       — Eksekusi perintah background
├── activities/                  — HelpActivity, SettingsActivity
├── api/file/                    — FileReceiverActivity (share/view)
├── event/                       — SystemEventReceiver (boot, konektivitas)
├── terminal/                    — RootView, daftar sesi, view client, style panel
├── terminal/io/                 — Extra keys, toolbar, keyboard, fullscreen
├── fragments/settings/          — Fragment preferensi per-app
│   ├── termux/                  — Debugging, terminal I/O, terminal view
│   ├── termux_api/              — Debugging Termux:API
│   ├── termux_float/            — Debugging Termux:Float
│   ├── termux_tasker/           — Debugging Termux:Tasker
│   └── termux_widget/           — Debugging Termux:Widget
├── models/                      — Enum UserAction
└── src/test/                    — Tes Robolectric (TermuxActivityTest, FileReceiverActivityTest)
```

## CARI DI SINI

| Tujuan | Lokasi |
|--------|--------|
| Lifecycle activity terminal | `TermuxActivity.java` — bind service, switch sesi, drawer, extra keys |
| Manajemen sesi shell | `TermuxService.java` — buat/hapus sesi, notifikasi foreground |
| Pipeline I/O sesi | `terminal/TermuxTerminalSessionActivityClient.java`, `terminal/TermuxTerminalSessionServiceClient.java` |
| Extra keys + toolbar | `terminal/io/TermuxTerminalExtraKeys.java`, `terminal/io/TerminalToolbarViewPager.java` |
| Pengaturan Termux | `fragments/settings/TermuxPreferencesFragment.java` + `fragments/settings/termux/` |
| Pengaturan plugin | `fragments/settings/Termux*PreferencesFragment.java` + subdir masing-masing |
| Intent file share/view | `api/file/FileReceiverActivity.java` |
| Instalasi bootstrap | `TermuxInstaller.java` — setup prefix di first-run |
| Perintah background | `RunCommandService.java`, `RunCommandBroadcastReceiver.java` |
| Styling / tema | `terminal/StylePanelFragment.java`, `terminal/HsvColorPickerView.java` |
| Tes | `src/test/java/com/termux/app/` — Robolectric, JUnit 4 |

## KONVENSI (Khusus Modul)

- **namespace**: `com.termux` (bukan `com.termux`). Nilai lama `com.termux` masih ada di `TermuxConstants` untuk kompatibilitas intent.
- **packageVariant**: Dikontrol env `TERMUX_PACKAGE_VARIANT`. Default `apt-android-7`, fallback `apt-android-5`.
- **Penamaan APK**: Pola `texmux-app_<variant>-<buildType>_<abi>.apk`. Dikontrol env `TERMUX_APK_VERSION_TAG`, `TERMUX_SPLIT_APKS_FOR_*_BUILDS`.
- **VersionName**: Validasi regex semver 2.0.0 di `validateVersionName()`.
- **Testing**: Robolectric 4.10, JUnit 4.13.2. `includeAndroidResources = true`.
- **Asset pipeline**: Task kustom `buildTermuxApiBinary`, `buildTermuxAmNative`, `copyTermuxApiApk` wiring ke mergeAssets. Build binary native dari `termux-api/native/` dan `termux-am-native-package/` ke `src/main/assets/`.
- **ProGuard**: Release build minify dengan `proguard-rules.pro`. Shrink resources dimatikan untuk reproducible build.
- **Signing**: Debug build pakai `testkey_untrusted.jks` (test key komunitas). Release signing dikonfigurasi eksternal per channel distribusi.

## KELAS PENTING

- **TermuxActivity** — Activity utama, host `TerminalView` dan `DrawerLayout`. Bind ke `TermuxService`, manage daftar sesi, handle intent (crash notify, reload style, permissions). Extend `AppCompatActivity`.
- **TermuxService** — Foreground service pemilik semua instance `TerminalSession`. Handle intent `RUN_COMMAND`, lifecycle sesi, notification channel. Implement `AppShellClient`, `TermuxSessionClient`.
- **TermuxApplication** — Entry aplikasi. Inisialisasi crash handler (`TermuxCrashUtils`), set default logging.
- **TermuxInstaller** — Ekstrak arsip bootstrap di first-run. Setup filesystem `$PREFIX`.
- **FileReceiverActivity** — Handle intent `ACTION_VIEW`/`ACTION_SEND` untuk operasi file di lingkungan Termux.
- **TermuxTerminalViewClient** — Penghubung antara `TerminalView` (dari modul `terminal-view`) dan `TermuxActivity`. Handle input events, URL detection, context menu, selection.
- **TermuxSessionsListViewController** — Daftar sesi di drawer kiri. Manage switching, rename, dan fail-safe creation sesi.
- **TermuxActivityRootView** — `FrameLayout` kustom yang handle window insets (keyboard, navigation bar) dan integrasi IME.
- **StylePanelFragment** — Bottom sheet styling: ukuran font, color scheme, cursor style, tema terminal.
- **TermuxTerminalExtraKeys** — Konfigurasi baris extra keys, parsing layout dari properties, pembuatan tombol dinamis.
- **TerminalToolbarViewPager** — ViewPager di area toolbar terminal, berisi halaman extra keys dan konten lain.
- **TermuxPreferencesFragment** — Layar pengaturan utama. Setiap plugin punya `Termux*PreferencesFragment` dan sub-fragment debugging sendiri.
