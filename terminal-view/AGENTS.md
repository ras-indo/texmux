# TERMINAL-VIEW

**Modul**: Android library (`com.texmux.view`). Custom View untuk rendering output terminal dan interaksi teks. Dependensi: `:terminal-emulator` dan AndroidX Annotation.

## STRUKTUR

```
terminal-view/src/main/java/com/termux/view/
  TerminalView.java              # View rendering utama (custom View Android)
  TerminalRenderer.java          # Font metrics, painting teks di Canvas
  TerminalViewClient.java        # Interface: callback host app (scale, tap, key, copy mode)
  GestureAndScaleRecognizer.java # Wrapper GestureDetector + ScaleGestureDetector
  support/
    PopupWindowCompatGingerbread.java  # Refleksi PopupWindow (API < 21)
  textselection/
    CursorController.java         # Interface kontrol kursor seleksi teks
    TextSelectionCursorController.java  # ActionMode, rentang seleksi, copy/paste
    TextSelectionHandleView.java  # Overlay PopupWindow handle yang bisa digeser
```

Total: 8 file Java, tanpa sub-subpackage.

## CARI DI SINI

| File | Tujuan |
|------|--------|
| `TerminalView.java` | Entry: `onDraw()`, `onTouchEvent()`, `onKeyDown()`, InputConnection, cursor blink, autofill, scroll |
| `TerminalRenderer.java` | Layout teks: `render()` gambar baris layar, `drawTextRun()` rendering per-karakter, font metrics |
| `TerminalViewClient.java` | Kontrak host app: mapping back/ctrl/alt/shift, copy mode, logging |
| `textselection/TextSelectionCursorController.java` | Logika seleksi: selection bounds, ActionMode, copy/paste |
| `textselection/TextSelectionHandleView.java` | Handle PopupWindow bisa digeser untuk atur rentang seleksi |

## KELAS PENTING

**TerminalView** (extends View). Pusat. Field: `mEmulator`, `mRenderer`, `mClient`, `mTextSelectionCursorController`, `mGestureRecognizer`. Menangani: draw via `onDraw() -> mRenderer.render()`, touch via `GestureAndScaleRecognizer` (scroll, fling, pinch-zoom, double-tap, long-press), keyboard via `onKeyDown()/onKeyUp()`, IME via custom `BaseInputConnection`, seleksi teks via `TextSelectionCursorController`, cursor blink via loop `Handler`, autofill untuk penyimpanan username/password.

**TerminalRenderer**. Drawing murni: maintain `TextPaint`, typeface, font metrics. `render()` iterate baris layar, panggil `drawTextRun()` per logical run. Hitung `mTopRow` berdasarkan scroll state.

**GestureAndScaleRecognizer**. Bungkus `GestureDetector` + `ScaleGestureDetector` Android. Dispatch ke listener TerminalView: `onScroll`, `onFling`, `onScale`, `onDoubleTap`, `onLongPress`, `onSingleTapUp`.

## KONVENSI

- Package namespace: `com.texmux.view` (texmux, bukan termux). Cocok dengan namespace build.gradle.
- Seleksi teks ikut pola `ActionMode` Android (API 11+). Interface `CursorController` abstraksi operasi handle kursor untuk PopupWindow kustom, bukan handle bawaan `TextView`.
- Rendering font pakai `android.text.TextPaint` dengan iterasi `char[]` manual, bukan `Spannable`/`StaticLayout`. Setiap karakter dipetakan ke sel di grid.
- Tidak ada XML layout di modul ini. Resource (`res/`) minimal (drawable untuk selection handles saja).
- `PopupWindowCompatGingerbread` pakai refleksi untuk `setWindowLayoutType`/`getWindowLayoutType` di API pre-Lollipop.

## DAMPAK PERUBAHAN

Dipakai oleh: `app/` (TermuxActivity, TermuxInstaller), `termux-shared/` (TermuxTerminalViewClientBase, TextIOActivity). Perubahan di `TerminalView.java` memengaruhi activity terminal utama dan plugin mana pun yang embed terminal.
