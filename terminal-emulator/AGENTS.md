# MODUL TERMINAL-EMULATOR

## RINGKASAN

Engine escape sequence VT100/xterm standalone dalam Java murni, JNI untuk I/O PTY. Tanpa dependensi framework Android. Dipakai oleh `terminal-view` dan `app` via `TerminalSession`.

## STRUKTUR

```
src/main/java/com/termux/terminal/   # 14 file Java
  TerminalEmulator.java    # State machine utama, processing escape
  TerminalBuffer.java      # Screen buffer + scrollback
  TerminalSession.java     # Merekatkan emulator ke thread I/O
  TerminalRow.java         # Satu baris: char[] + TextStyle[]
  TextStyle.java           # Value object style, dikemas dalam int
  TerminalColors.java      # Definisi color scheme
  TerminalColorScheme.java # Scheme individu
  TerminalOutput.java      # Interface output
  TerminalSessionClient.java # Callback event sesi
  KeyHandler.java          # Map key Android ke escape sequence
  ByteQueue.java           # Circular buffer lock-free
  WcWidth.java             # Unicode wcwidth
  JNI.java                 # Deklarasi method native
  Logger.java              # Logging modul
src/main/jni/              # termux.c + Android.mk
src/test/java/com/termux/terminal/   # 19 file tes
```

## KELAS PENTING

**TerminalEmulator** (2642 baris). State machine inti: `inputProcess()`, `doEsc()`, `doCsi()`, `doOSC()`. Mengelola main/alt screen buffer, cursor, scroll region, charset G0-G3.

**TerminalBuffer**. Array `TerminalRow[]` untuk baris layar + ring scrollback. Operasi kunci: `set()`, `getLine()`, `resize()` dengan row reflow, `scroll()`, `blockCopy()`, `blockSet()`.

**TerminalSession**. Membungkus emulator + thread I/O. Baca PTY dalam loop, kirim byte ke `TerminalEmulator.inputProcess()`. Handle lifecycle shell, window change.

**TextStyle**. Encoding `int` immutable: foreground(9b), background(9b), effects(8b), reserved(6b). Bit untuk bold, dim, italic, underline, blink, inverse, invisible, strikethrough.

**KeyHandler**. Memetakan `KeyEvent` Android + modifier ke escape sequence VT100/xterm.

**ByteQueue**. Circular buffer lock-free untuk stream byte PTY. Producer-consumer antara I/O dan emulator.

## CARI DI SINI

| Tugas | File |
|-------|------|
| Parsing escape | `TerminalEmulator.java` — `doEsc()`/`doCsi()` |
| Screen buffer | `TerminalBuffer.java` |
| Atribut teks | `TextStyle.java`, `TerminalColors.java` |
| Input keyboard | `KeyHandler.java` |
| Lebar Unicode | `WcWidth.java` |
| Native JNI | `JNI.java` + `src/main/jni/termux.c` |
| Referensi VT100 | Javadoc `TerminalEmulator.java` (invisible-island, vt100.net) |

## POLA TES

Tes extend `TerminalTestCase`. Helper: `enterString()`, `assertLinesAre()`, `assertCursorAt()`. Diorganisir per kategori escape: `DecSetTest`, `CursorAndScreenTest`, `ControlSequenceIntroducerTest`, `ScrollRegionTest`, dll.

## KONVENSI

- State machine byte single-pass. Urutan baru: tambah konstanta state, handle di `doEsc()`/`doCsi()`/`doOSC()`.
- JNI minimal (PTY create/read/write saja). Semua logika terminal di Java.
- Kelas inti tanpa dependensi Android context. Bisa di-unit-test tanpa device.
- `TextStyle` dikemas dalam `int` untuk efisiensi memori.
- `libtermux.so` prebuilt di-commit ke `jni/libs/`. Hanya `termux.c` yang source of truth.
