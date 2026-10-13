# termux-shared AGENTS.md

**Path:** `termux-shared/`
**File Java:** 121 (modul terbesar)
**Peran:** Library bersama antara app Termux dan plugin API. Konstanta, utilitas, preferensi, eksekusi shell, notifikasi, operasi file.

## ORGANISASI DUA ZONA

| Zona | Package | Cakupan |
|------|---------|---------|
| Umum | `com.termux.shared.*` | Utilitas Android-agnostic (file, net, logger, markdown, reflection, models) |
| Termux | `com.termux.shared.termux.*` | Logika app (extrakeys, shell, terminal, crash, notification, plugins, theme) |

Aturan: utilitas mandiri yang bisa dipakai app Android mana pun → masuk `shared/`. Yang mereferensi konsep/path Termux → masuk `termux/`.

## PETA PAKET

```
android/                     # Context wrappers, resource utils
file/filesystem/             # Operasi file, utilitas filesystem
logger/                      # LogEntry, LogConfig, logging terstruktur
markdown/                    # Parsing Markdown
net/socket/local/             # UNIX local socket (JNI: src/main/cpp/local-socket.cpp)
net/uri/ net/url/             # Manipulasi URI/URL
notification/                # Channel, builder, helper
reflection/                  # Utilitas Java reflection
settings/preferences/        # Hirarki AppSharedPreferences
settings/properties/         # Parsing file .termux/termux.properties
shell/command/               # Pipeline eksekusi: konstanta, env, result, runner
shell/command/runner/app/    # AppShell — runner perintah utama
termux/extrakeys/            # ExtraKeysView + konstanta
termux/shell/                # Command env spesifik Termux + terminal runner
termux/settings/             # Key preferensi Termux + konstanta properti
termux/terminal/ + io/       # Manajemen sesi, bridge IO, JNI loginshell
termux/theme/                # Tema warna
termux/crash/                # Pelaporan crash
termux/notification/         # Channel notifikasi Termux
termux/plugins/              # Helper plugin API
```

## ALUR EKSEKUSI PERINTAH

`AppShell` (runner/app) mengatur eksekusi shell. Submit via protokol intent `TermuxServiceManager`. Hasil di `shell/command/result/`. Varian terminal di `termux/shell/command/runner/terminal/`.

## KELAS PENTING

- `TermuxConstants` — sumber kebenaran tunggal untuk path, nama paket, intent actions
- `AppShell` — runner perintah utama, lifecycle process shell
- `ExtraKeysView`, `ExtraKeysInfo`, `ExtraKeysConstants` — UI extra keys
- `TermuxPropertyConstants` — key file properti
- Hirarki `AppSharedPreferences` — semua akses SharedPreferences
- `TermuxTerminalSession` / `TermuxTerminalIO` — sesi terminal + bridge IO JNI

## BUILD

`build.gradle` di root modul. Java 11+, AGP 8.13, minSdk 21, targetSdk 28, compileSdk 36. Native JNI di `src/main/cpp/`. Tes Android di `src/androidTest/`. 121 sumber Java, nol Kotlin di modul ini.

## PERINGATAN

Jangan modifikasi `TermuxConstants` tanpa verifikasi ke 5 modul konsumen.
