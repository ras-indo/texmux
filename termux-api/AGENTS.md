# AGENTS.md — Modul Termux:API

## Ringkasan

App plugin yang mengekspos API sistem Android (battery, camera, clipboard, sensor, telephony, dll.) ke lingkungan shell Termux via skrip command-line. Berjalan sebagai APK terpisah yang ditandatangani dengan key yang sama dengan app Termux utama. TIDAK mengeksekusi perintah sendiri — menerima intent dari app Termux utama dan mengembalikan hasil.

## Package

Pakai `com.termux.api` (BUKAN `com.termux.api`). Ini disengaja dan berbeda dari modul lain. Semua file sumber di bawah package ini.

## Struktur

| Path | Tujuan |
|------|--------|
| `apis/` | Implementasi endpoint API — satu kelas Java per API (38 file) |
| `activities/` | TermuxAPIMainActivity, TermuxApiPermissionActivity |
| `client/` | TermuxApiClient — interface sisi client untuk panggilan API |
| `settings/` | TermuxAPISettingsActivity, fragment preferensi |
| `util/` | ApiRequest, JsonUtils, PluginUtils, ResultReturner, ViewUtils |
| `native/scripts/` | Template skrip shell yang di-deploy via paket termux-api (file .in) |
| root (com.termux.api) | ApiLogConfig, TermuxAPIConstants, TermuxApiReceiver, SocketListener, DirectDispatcher, NativeBridge |

## Cari di Sini

| Tugas | Path |
|-------|------|
| Tambah/ubah endpoint API | `apis/<Nama>API.java` — satu kelas per API |
| Logika dispatch/receiver API | `TermuxApiReceiver.java`, `SocketListener.java`, `DirectDispatcher.java` |
| Konstanta (nama paket, receiver) | `TermuxAPIConstants.java` |
| Konfigurasi logging | `ApiLogConfig.java` |
| Pengaturan/Preferensi | `settings/activities/TermuxAPISettingsActivity.java`, `settings/fragments/` |
| Skrip bantuan | `native/scripts/termux-*.in` |
| Konfigurasi build | `app/build.gradle` (modul sendiri, bukan root) |
| Manifest | `app/src/main/AndroidManifest.xml` (terpisah dari app utama) |
| Assets | `app/src/main/assets/` |
| Resources | `app/src/main/res/` |

## Kelas Penting

- **ApiLogConfig** — konfigurasi logging terpusat untuk modul API
- **TermuxApiReceiver** — broadcast receiver yang merutekan intent ke handler API yang tepat
- **DirectDispatcher** — mekanisme dispatch alternatif untuk panggilan API
- **SocketListener** — menangani komunikasi socket-based dengan binary termux-api
- **ResultReturner** — utilitas untuk mengembalikan hasil terstruktur ke pemanggil
- **ApiRequest** — model data request untuk panggilan API masuk

## Daftar API (38 endpoint di `apis/`)

Audio, BatteryStatus, Bluetooth, Brightness, CallLog, CameraInfo, CameraPhoto, Clipboard, ContactList, Dialog, Download, Fingerprint, Infrared, JobScheduler, Keystore, Location, MediaPlayer, MediaScanner, MicRecorder, Nfc, Notification, NotificationList, SAF, Sensor, Share, SmsInbox, SmsSend, SpeechToText, StorageGet, Telephony, TextToSpeech, Toast, Torch, Usb, Vibrate, Volume, Wallpaper, Wifi.

## Catatan

- Setiap kelas API menerima `ApiRequest` dan menulis hasil via `ResultReturner`
- Pakai sharedUserId `com.termux` — harus ditandatangani dengan key yang sama dengan app Termux utama
- Binary C client native (`termux-api`) ada di repo terpisah `termux-api-package`, bukan di sini
- Skrip shell di `native/scripts/` adalah template `.in` yang diproses saat package build
