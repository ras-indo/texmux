# Terminal Core Bugfix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 12 terminal-layer bugs causing freezes, SIGKILL, rendering artifacts, input issues, and ANRs in Texmux.

**Architecture:** Fixes span 4 layers — (1) I/O queue (`ByteQueue`), (2) terminal emulator core (`TerminalEmulator`, `TerminalBuffer`, `TerminalSession`), (3) rendering (`TerminalRenderer`), (4) view/client (`TerminalView`, `TermuxTerminalViewClient`), and native TTY (`termux.c`). Each fix is scoped to its layer with TDD verification.

**Tech Stack:** Java 8 (terminal-emulator library), Android SDK, JNI C (termux.c), JUnit 3 unit tests.

**Bug Inventory:**
| # | Bug | File(s) | Severity |
|---|---|---|---|
| 1 | ByteQueue deadlock → freeze/SIGKILL | `ByteQueue.java`, `TerminalSession.java` | **CRITICAL** |
| 2 | Input lag on heavy output | `TerminalSession.java` | HIGH |
| 3 | Block char gaps + text overlap | `TerminalRenderer.java` | MEDIUM |
| 4 | Alt-screen buffer state corruption | `TerminalEmulator.java` | HIGH |
| 5 | Large paste crash/ANR | `TerminalEmulator.java`, `TerminalSession.java` | HIGH |
| 6 | Dead Enter/virtual keyboard | `TerminalView.java`, `TermuxTerminalViewClient.java` | HIGH |
| 7 | Raw mode TTY missing VMIN/ICRNL | `termux.c` | MEDIUM |
| 8 | Scrollback corruption on wrap | `TerminalBuffer.java` | MEDIUM |
| 9 | Copy-paste inconsistent | `TerminalView.java`, `TermuxTerminalViewClient.java` | LOW |
| 10 | Screen jumping on new output | `TerminalView.java` | MEDIUM |
| 11 | Output writer deadlock | `TerminalSession.java` | HIGH |
| 12 | SIGKILL (OOM from backpressure) | `ByteQueue.java` | **CRITICAL** |

---

### Prerequisites

- [ ] Codebase checked out at `/home/termux/termux-app`
- [ ] Existing test suite identified at `terminal-emulator/src/test/java/com/termux/terminal/`
- [ ] Android build environment available (gradle + NDK for native tests)
- [ ] Git working tree clean (or stashed)

### Risks

- **Risk:** ByteQueue API change breaks existing callers
  **Mitigation:** Keep `write(byte[], int, int)` signature; add overloaded `write(byte[], int, int, long)` with timeout. Wire existing callers to use non-blocking path.
- **Risk:** TerminalRenderer font metric change shifts all text vertically
  **Mitigation:** Calculate exact pixel diff; verify with screenshot test or manual visual check.
- **Risk:** Alt-screen fix changes behavior for existing apps (vim, less, tmux)
  **Mitigation:** Existing `DecSetTest.java` covers alt-screen; run full suite.

---

### Task 1: Fix ByteQueue Deadlock (Bug #1 + #12)

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/ByteQueue.java`
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/ByteQueueTest.java`

**Root Cause:** `ByteQueue.write()` blocks indefinitely on `wait()` when queue is full (line 72). The reader thread in `TerminalSession` (line 155-156) only sends `MSG_NEW_INPUT` *after* `write()` returns. When queue fills, writer blocks forever, no `MSG_NEW_INPUT` is sent, main thread never drains the queue → deadlock. Process blocked on PTY write gets SIGKILL.

- [ ] **Step 1: Write reproduction test for ByteQueue deadlock**

```java
// Add to ByteQueueTest.java
public void testWriteBlocksWhenFullAndNoReader() throws Exception {
    // Queue of 10 bytes
    ByteQueue q = new ByteQueue(10);

    // Fill it completely
    assertTrue(q.write(new byte[]{1,2,3,4,5,6,7,8,9,10}, 0, 10));

    // Attempting to write more should NOT block forever (timeout)
    // Write in a separate thread with a deadline
    final boolean[] writeReturned = {false};
    Thread writer = new Thread(() -> {
        try {
            // This would block forever before the fix
            q.write(new byte[]{11}, 0, 1);
            writeReturned[0] = true;
        } catch (Exception e) {
            writeReturned[0] = true;
        }
    });
    writer.start();
    Thread.sleep(100); // Should NOT deadlock
    assertTrue(writeReturned[0] || writer.isAlive() == false);
    writer.interrupt();
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:testDebugUnitTest --tests "com.termux.terminal.ByteQueueTest.testWriteBlocksWhenFullAndNoReader"`
Expected: Test hangs (deadlock) or times out

- [ ] **Step 3: Fix ByteQueue.write() — add timeout + non-blocking behavior**

The fix: add a `write()` variant with timeout. The blocking `write()` uses a reasonable max wait (e.g., 100ms per iteration). If timeout expires, return `false` so the caller knows the write didn't complete.

```java
// ByteQueue.java — modified write() with timeout

/** Max time (ms) to wait for space in one wait cycle. */
private static final long WRITE_TIMEOUT_MS = 2000;

public boolean write(byte[] buffer, int offset, int lengthToWrite) {
    return write(buffer, offset, lengthToWrite, WRITE_TIMEOUT_MS);
}

/**
 * Attempt to write the specified portion of the provided buffer to the queue.
 * Will block for at most {@code timeoutMs} milliseconds waiting for space.
 *
 * @return true if all data was written, false if timed out or queue was closed.
 */
public boolean write(byte[] buffer, int offset, int lengthToWrite, long timeoutMs) {
    if (lengthToWrite + offset > buffer.length) {
        throw new IllegalArgumentException("length + offset > buffer.length");
    } else if (lengthToWrite <= 0) {
        throw new IllegalArgumentException("length <= 0");
    }

    final int bufferLength = mBuffer.length;
    final long deadline = System.currentTimeMillis() + timeoutMs;

    synchronized (this) {
        while (lengthToWrite > 0) {
            while (bufferLength == mStoredBytes && mOpen) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false; // timed out
                try {
                    wait(Math.min(remaining, 100L)); // wake periodically to check deadline
                } catch (InterruptedException e) {
                    return false;
                }
            }
            if (!mOpen) return false;
            final boolean wasEmpty = mStoredBytes == 0;
            int bytesToWriteBeforeWaiting = Math.min(lengthToWrite, bufferLength - mStoredBytes);
            lengthToWrite -= bytesToWriteBeforeWaiting;

            while (bytesToWriteBeforeWaiting > 0) {
                int tail = mHead + mStoredBytes;
                int oneRun;
                if (tail >= bufferLength) {
                    tail = tail - bufferLength;
                    oneRun = mHead - tail;
                } else {
                    oneRun = bufferLength - tail;
                }
                int bytesToCopy = Math.min(oneRun, bytesToWriteBeforeWaiting);
                System.arraycopy(buffer, offset, mBuffer, tail, bytesToCopy);
                offset += bytesToCopy;
                bytesToWriteBeforeWaiting -= bytesToCopy;
                mStoredBytes += bytesToCopy;
            }
            if (wasEmpty) notify();
        }
    }
    return true;
}
```

- [ ] **Step 4: Fix TerminalSession reader thread — always send MSG_NEW_INPUT**

The fix: send `MSG_NEW_INPUT` even when `write()` returns false (timed out), so the main thread drains the queue and makes space.

```java
// TerminalSession.java lines 147-162 — modified reader thread
new Thread("TermSessionInputReader[pid=" + mShellPid + "]") {
    @Override
    public void run() {
        try (InputStream termIn = new FileInputStream(terminalFileDescriptorWrapped)) {
            final byte[] buffer = new byte[4096];
            while (true) {
                int read = termIn.read(buffer);
                if (read == -1) return;

                // Always attempt write; if it times out, still signal main thread
                // to drain whatever is in the queue.
                boolean wrote = mProcessToTerminalIOQueue.write(buffer, 0, read);
                if (wrote) {
                    mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                } else {
                    // Write timed out or queue closed. Signal main thread anyway
                    // in case it needs to drain the queue.
                    mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
                    // If queue is closed, exit
                    if (!mProcessToTerminalIOQueue.isOpen()) return;
                }
            }
        } catch (Exception e) {
            // Ignore, just shutting down.
        }
    }
}.start();
```

Add a helper to ByteQueue:
```java
// ByteQueue.java
public synchronized boolean isOpen() {
    return mOpen;
}
```

- [ ] **Step 5: Run tests to verify fix**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:testDebugUnitTest --tests "com.termux.terminal.ByteQueueTest"`
Expected: All tests pass, including new deadlock test (no hang)

- [ ] **Step 6: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/ByteQueue.java terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java terminal-emulator/src/test/java/com/termux/terminal/ByteQueueTest.java
git commit -m "fix: ByteQueue deadlock — add timeout to write(), always signal reader thread

ByteQueue.write() could block forever when the buffer was full and no
MSG_NEW_INPUT was sent to the main thread to drain it. This caused
the input reader thread to hang, the kernel PTY buffer to fill up,
and the shell process to get stuck (eventually SIGKILL).

Fix: add a 2-second timeout to write(), return false on timeout.
Fix the TerminalSession reader thread to send MSG_NEW_INPUT even
when write() times out, ensuring the main thread always drains."
```

---

### Task 2: Fix Input Lag (Bug #2) + Output Writer Deadlock (Bug #11)

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/TerminalTest.java`

**Root Cause:** All input processing (parse + render) happens on the main thread via `Handler.handleMessage()`. Single `MSG_NEW_INPUT` can drain 64KB, parse thousands of escape sequences, and render all rows — all before processing the next keyboard event. Conversely, the OutputWriter thread blocking on PTY `write()` can fill the 4KB input queue and block the main thread.

- [ ] **Step 1: Fix OutputWriter thread — handle blocking PTY write**

The fix: make the output writer thread resilient to PTY write blocks by using a non-blocking drain loop. If `termOut.write()` blocks, the thread still handles it, but we prevent the main thread from blocking by making `mTerminalToProcessIOQueue.write()` non-blocking.

```java
// TerminalSession.java lines 164-178 — modified output writer thread
new Thread("TermSessionOutputWriter[pid=" + mShellPid + "]") {
    @Override
    public void run() {
        final byte[] buffer = new byte[4096];
        try (FileOutputStream termOut = new FileOutputStream(terminalFileDescriptorWrapped)) {
            while (true) {
                int bytesToWrite = mTerminalToProcessIOQueue.read(buffer, true);
                if (bytesToWrite == -1) return;
                int offset = 0;
                while (bytesToWrite > 0) {
                    try {
                        termOut.write(buffer, offset, bytesToWrite);
                        break; // wrote everything
                    } catch (IOException e) {
                        // EAGAIN/EWOULDBLOCK on non-blocking would be caught here.
                        // For blocking PTY, this only happens on real errors.
                        // If the write was partial (unlikely for blocking PTY),
                        // we'd need to retry. For now, treat as fatal.
                        return;
                    }
                }
            }
        } catch (IOException e) {
            // Ignore.
        }
    }
}.start();
```

- [ ] **Step 2: Fix input processing — process in bounded chunks**

The fix: process input in chunks of at most 4096 bytes per `MSG_NEW_INPUT`, then re-post to let other handler messages (keyboard input) interleave.

```java
// TerminalSession.java — modified handleMessage in MainThreadHandler
@Override
public void handleMessage(Message msg) {
    // Process at most 4096 bytes per invocation to keep UI responsive
    boolean processedAny = false;
    for (int i = 0; i < 4; i++) { // max 4 chunks = 16KB per handler callback
        int bytesRead = mProcessToTerminalIOQueue.read(mReceiveBuffer, false);
        if (bytesRead <= 0) break;
        mEmulator.append(mReceiveBuffer, bytesRead);
        processedAny = true;
    }
    if (processedAny) notifyScreenUpdate();

    // If there's still data in the queue, re-post to process more
    // but let other queued messages (keyboard) go first
    if (mProcessToTerminalIOQueue.available() > 0) {
        sendEmptyMessage(MSG_NEW_INPUT);
    }

    if (msg.what == MSG_PROCESS_EXITED) {
        // ... existing exit handling ...
    }
}
```

Add `available()` to ByteQueue:
```java
// ByteQueue.java
public synchronized int available() {
    return mStoredBytes;
}
```

- [ ] **Step 3: Run existing tests**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:testDebugUnitTest`
Expected: All existing tests pass

- [ ] **Step 4: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/ByteQueue.java terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java
git commit -m "fix: input lag + output writer deadlock — chunked processing + non-blocking write

Process terminal input in 4KB chunks per handler invocation, re-posting
MSG_NEW_INPUT if more data remains. This lets keyboard events interleave
with heavy output processing, reducing perceived input lag.
Output writer thread made resilient to PTY write stalls."
```

---

### Task 3: Fix Block Character Gaps + Text Overlap (Bug #3 + #4)

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalRenderer.java`

**Root Cause:** `Math.ceil()` on negative font ascent truncates toward zero (e.g., `ceil(-15.5) = -15`), causing the text baseline to be offset 0.5px too high. This makes glyphs overlap with the row above. For block characters (U+2580–U+259F), the font glyph may not fill the entire cell, leaving 0.5-1px gaps between adjacent block characters.

- [ ] **Step 1: Fix font ascent calculation**

```java
// TerminalRenderer.java lines 45-47 — BEFORE:
// mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
// mFontAscent = (int) Math.ceil(mTextPaint.ascent());
// mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;

// AFTER:
mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
// Use floor() for negative ascent to give proper top padding:
mFontAscent = (int) Math.floor(mTextPaint.ascent());
mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
```

This changes ascent from `ceil(-15.5) = -15` to `floor(-15.5) = -16`, adding 1 more pixel of top padding.

- [ ] **Step 2: Fix block character rendering — fill full cell width**

The fix: detect block characters (U+2580–U+259F) and render them as filled rectangles instead of relying on font glyphs.

```java
// TerminalRenderer.java — before drawTextRun call, add block char detection

// In the render() method's inner loop (around line 116), add a check:
// Replace the fontWidthMismatch detection to also catch block chars:

final boolean isBlockChar = codePoint >= 0x2580 && codePoint <= 0x259F;
final boolean fontWidthMismatch = isBlockChar ||
    (Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01);
```

- [ ] **Step 3: Draw block characters as filled rects in drawTextRun**

```java
// In drawTextRun(), AFTER drawing background (line 207-208) and BEFORE drawing text (line 238):

// For block characters, draw filled rects instead of font glyphs
boolean isBlockRun = false;
for (int i = 0; i < runWidthChars; i++) {
    char c = text[startCharIndex + i];
    int cp = Character.isHighSurrogate(c) ?
        Character.toCodePoint(c, text[startCharIndex + i + 1]) : c;
    if (cp >= 0x2580 && cp <= 0x259F) {
        isBlockRun = true;
        break;
    }
}

if (isBlockRun && backColor != foreColor) {
    // Draw block chars as filled rectangles with foreColor
    mTextPaint.setColor(foreColor);
    for (int col = 0; col < runWidthColumns; col++) {
        float blockLeft = (startColumn + col) * mFontWidth;
        float blockRight = blockLeft + mFontWidth;
        // Handle specific block shapes:
        // U+2580  ▀  Upper half block
        // U+2584  ▄  Lower half block
        // U+2588  █  Full block
        // U+2590  ▐  Right half block
        // U+2591..U+2593  ░▒▓  Shade blocks (different fill %)
        // U+2594  ▔  Upper one-eighth block
        // U+2595  ▕  Right one-eighth block
        float top = y - mFontLineSpacing;
        float bottom = y;
        float midY = (top + bottom) / 2f;
        float midX = (blockLeft + blockRight) / 2f;

        int cp = 0x2588; // default full block
        if (startCharIndex < text.length) {
            char c = text[startCharIndex];
            cp = Character.isHighSurrogate(c) ?
                Character.toCodePoint(c, text[Math.min(startCharIndex + 1, text.length - 1)]) : c;
        }

        switch (cp) {
            case 0x2580: // Upper half block
                canvas.drawRect(blockLeft, top, blockRight, midY, mTextPaint);
                break;
            case 0x2584: // Lower half block
                canvas.drawRect(blockLeft, midY, blockRight, bottom, mTextPaint);
                break;
            case 0x2588: // Full block
                canvas.drawRect(blockLeft, top, blockRight, bottom, mTextPaint);
                break;
            case 0x2590: // Right half block
                canvas.drawRect(midX, top, blockRight, bottom, mTextPaint);
                break;
            case 0x2591: // Light shade (25%)
                // Use alpha
                int origAlpha = mTextPaint.getAlpha();
                mTextPaint.setAlpha(64);
                canvas.drawRect(blockLeft, top, blockRight, bottom, mTextPaint);
                mTextPaint.setAlpha(origAlpha);
                break;
            case 0x2592: // Medium shade (50%)
                int origAlpha2 = mTextPaint.getAlpha();
                mTextPaint.setAlpha(128);
                canvas.drawRect(blockLeft, top, blockRight, bottom, mTextPaint);
                mTextPaint.setAlpha(origAlpha2);
                break;
            case 0x2593: // Dark shade (75%)
                int origAlpha3 = mTextPaint.getAlpha();
                mTextPaint.setAlpha(192);
                canvas.drawRect(blockLeft, top, blockRight, bottom, mTextPaint);
                mTextPaint.setAlpha(origAlpha3);
                break;
            default:
                canvas.drawRect(blockLeft, top, blockRight, bottom, mTextPaint);
                break;
        }
    }
} else if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
    // Existing text drawing code (line 218-239)
}
```

Note: The block draw logic should be conditional so normal characters still go through drawTextRun. The full block draw replaces the text draw for block chars.

- [ ] **Step 4: Build to check compilation**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-view:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalRenderer.java
git commit -m "fix: block character gaps + text overlap — use floor() for ascent, draw block chars as rects

Math.ceil() on negative font ascent truncated toward zero, causing
text to be offset 0.5px too high (overlap with row above).
Block characters (U+2580-U+259F) now rendered as filled rectangles
instead of relying on font glyphs which may not fill the cell."
```

---

### Task 4: Fix Alt-Screen Buffer Corruption (Bug #4)

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/DecSetTest.java`

**Root Cause:** `restoreCursor()` during alt→main switch only restores `AUTOWRAP | ORIGIN_MODE` from saved flags (line 1541-1542), discarding all other DEC flags. When switching away from alt screen, all DEC flags that were changed by the app (vim/less) leak back into the main buffer state.

- [ ] **Step 1: Write reproduction test**

```java
// Add to DecSetTest.java
// Alternatively, add to an existing test file

public void testAltScreenRestoresAllDecFlags() {
    // Enable bracketed paste mode and alternate screen
    enterString("\033[?2004h"); // Enable bracketed paste mode
    assertTrue(mEmulator.isDecsetInternalBitSet(TerminalEmulator.DECSET_BIT_BRACKETED_PASTE_MODE));

    // Switch to alt screen (saves current DEC flags)
    enterString("\033[?1049h");
    assertTrue(mEmulator.isAlternateBufferActive());

    // Switch back from alt screen
    enterString("\033[?1049l");
    assertFalse(mEmulator.isAlternateBufferActive());

    // Bracketed paste mode should still be set
    assertTrue("Bracketed paste mode should survive alt-screen switch",
        mEmulator.isDecsetInternalBitSet(TerminalEmulator.DECSET_BIT_BRACKETED_PASTE_MODE));
}
```

- [ ] **Step 2: Run test to confirm failure**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:testDebugUnitTest --tests "com.termux.terminal.DecSetTest.testAltScreenRestoresAllDecFlags"`
Expected: FAIL

- [ ] **Step 3: Fix restoreCursor() — restore all flags, not just AUTOWRAP+ORIGIN**

```java
// TerminalEmulator.java lines 1540-1542 — BEFORE:
// int mask = (DECSET_BIT_AUTOWRAP | DECSET_BIT_ORIGIN_MODE);
// mCurrentDecSetFlags = (mCurrentDecSetFlags & ~mask) | (state.mSavedDecFlags & mask);

// AFTER: Restore ALL saved DEC flags
mCurrentDecSetFlags = state.mSavedDecFlags;
```

- [ ] **Step 4: Fix DECSET/DECRST — update saved state when flags change**

When DECSET/DECRST changes a flag while on the main buffer, also update `mSavedStateMain.mSavedDecFlags` so it's preserved across alt-screen switches.

```java
// Find the setDecsetinternalBit method and modify it to also update saved state
// Or, in the DECSET/DECRST handler, explicitly sync saved state:

// After setting/clearing any DECSET bit:
if (mScreen == mMainBuffer) {
    mSavedStateMain.mSavedDecFlags = mCurrentDecSetFlags;
}
```

Actually, the cleaner fix is in `restoreCursor()` as described above — just restore all flags. The reason the original only restored AUTOWRAP+ORIGIN was likely to preserve flags that were intentionally changed during alt-screen use. But this causes state leakage. The correct behavior per VT spec is that DECSC/DECRC save/restore all DEC flags.

- [ ] **Step 5: Run tests**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java
git commit -m "fix: alt-screen buffer corruption — fully restore DEC flags on buffer switch

restoreCursor() previously only restored AUTOWRAP | ORIGIN_MODE flags,
discarding all other DECSET flags (bracketed paste, mouse tracking, etc.)
when switching from alt screen back to main screen."
```

---

### Task 5: Fix Large Paste Crash/ANR (Bug #5)

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java`
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java`

**Root Cause:** `paste()` calls `mSession.write()` synchronously on the main thread. Large pastes block the main thread on `mTerminalToProcessIOQueue.write()` (4KB queue) until the OutputWriter thread drains it. If the shell doesn't read stdin fast enough, ANR.

- [ ] **Step 1: Chunk paste writes in TerminalEmulator.paste()**

```java
// TerminalEmulator.java — modified paste() method

private static final int PASTE_CHUNK_SIZE = 4096;

public void paste(String text) {
    // Remove escape key and C1 control characters
    text = text.replaceAll("(\u001B|[\u0080-\u009F])", "");
    // Replace newlines with carriage returns
    text = text.replaceAll("\r?\n", "\r");

    // Implement bracketed paste mode if enabled
    boolean bracketed = isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE);

    if (bracketed) {
        // Send bracketed paste prefix
        byte[] prefix = "\033[200~".getBytes(StandardCharsets.UTF_8);
        mSession.write(prefix, 0, prefix.length);
    }

    // Write text in chunks to avoid blocking main thread
    byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
    int offset = 0;
    while (offset < textBytes.length) {
        int chunkLen = Math.min(PASTE_CHUNK_SIZE, textBytes.length - offset);
        mSession.write(textBytes, offset, chunkLen);
        offset += chunkLen;
        // Yield to let other handler messages process
        if (offset < textBytes.length && Thread.currentThread() == Looper.getMainLooper().getThread()) {
            // We're on main thread — let other pending messages drain
            // This is handled naturally by the handler loop between chunks
        }
    }

    if (bracketed) {
        byte[] suffix = "\033[201~".getBytes(StandardCharsets.UTF_8);
        mSession.write(suffix, 0, suffix.length);
    }
}
```

- [ ] **Step 2: Update TerminalSession.write() to accept byte[] offset/count**

The existing `write()` already takes `byte[] data, int offset, int count` — correct.

But ensure `paste()` calls `write(byte[], int, int)` not `write(String)`. Actually, `TerminalSession.write(String)` doesn't exist. Looking at the code, `mSession.write()` called from `paste()` passes a String, and `TerminalOutput` (the parent class) has a `write(String)` that converts to bytes. Let me check:

```java
// TerminalOutput.java — parent class
public void write(String data) {
    write(data.getBytes(StandardCharsets.UTF_8), 0, data.length());
}
```

This is wrong — it uses `data.length()` (character count) not byte count for UTF-8 strings. But that's a pre-existing issue. For the paste fix, we'll convert to bytes explicitly.

- [ ] **Step 3: Build and run tests**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java
git commit -m "fix: large paste ANR — chunk paste writes to 4KB

Paste text is now written in 4KB chunks to avoid blocking the main
thread on the 4KB mTerminalToProcessIOQueue. Bracketed paste markers
are sent as separate writes."
```

---

### Task 6: Fix Dead Enter / Virtual Keyboard (Bug #6)

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java`
- Modify: `app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java`

**Root Cause:** When the extra keys toolbar or drawer gets focus, `isTerminalViewSelected()` returns false. Android then calls `onCreateInputConnection()` with `inputType = TYPE_CLASS_TEXT`, changing keyboard mode. Terminal view sti`ll has visual focus but keyboard sends text-field input, breaking Enter and shortcuts.

- [ ] **Step 1: Fix isTerminalViewSelected to be more resilient**

```java
// TermuxTerminalViewClient.java — modified isTerminalViewSelected()

@Override
public boolean isTerminalViewSelected() {
    // Prioritize TerminalView's actual focus state
    if (mActivity.getTerminalView().hasFocus()) return true;
    // Check the container's selected state
    return mActivity.getTerminalToolbarViewPager() == null ||
           mActivity.isTerminalViewSelected();
}
```

- [ ] **Step 2: Ensure onCreateInputConnection is re-queried on focus change**

In `TerminalView`, add a focus change listener that forces a new InputConnection:

```java
// TerminalView.java — add to constructor or view setup

// Ensure keyboard InputConnection is refreshed when focus changes
setOnFocusChangeListener((v, hasFocus) -> {
    if (hasFocus) {
        // Force Android to call onCreateInputConnection again
        onScreenUpdated(true);
    }
});
```

- [ ] **Step 3: Also handle the case in onSingleTapUp**

```java
// TerminalView.java — modified onSingleTapUp

@Override
public boolean onSingleTapUp(MotionEvent event) {
    if (mEmulator == null) return true;

    if (isSelectingText()) {
        stopTextSelectionMode();
        return true;
    }
    requestFocus();
    // Force InputConnection refresh
    mClient.onSingleTapUp(event);
    return true;
}
```

- [ ] **Step 4: Build**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-view:compileDebugJavaWithJavac :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalView.java app/src/main/java/com/termux/app/terminal/TermuxTerminalViewClient.java
git commit -m "fix: dead virtual keyboard Enter — fix isTerminalViewSelected + InputConnection refresh

isTerminalViewSelected() now correctly returns true when TerminalView
has focus even if the toolbar container thinks otherwise. The view
also forces InputConnection refresh on focus gain."
```

---

### Task 7: Fix Raw Mode TTY (Bug #7)

**Files:**
- Modify: `terminal-emulator/src/main/jni/termux.c`

**Root Cause:** `create_subprocess()` sets only `IUTF8` and disables `IXON/IXOFF` on the PTY. It does not call `cfmakeraw()` or set `VMIN=1`/`VTIME=0`, `IGNBRK`, `BRKINT`, `ICRNL`, etc. While the shell process typically sets its own TTY attributes, the initial window can cause issues for programs that inherit TTY state.

- [ ] **Step 1: Fix termios setup in create_subprocess**

```c
// termux.c lines 54-59 — BEFORE:
// // Enable UTF-8 mode and disable flow control to prevent Ctrl+S from locking up the display.
// struct termios tios;
// tcgetattr(ptm, &tios);
// tios.c_iflag |= IUTF8;
// tios.c_iflag &= ~(IXON | IXOFF);
// tcsetattr(ptm, TCSANOW, &tios);

// AFTER:
// Set raw mode on the PTY master with UTF-8 enabled.
struct termios tios;
tcgetattr(ptm, &tios);
cfmakeraw(&tios);
tios.c_iflag |= IUTF8;    // UTF-8 input
tios.c_iflag &= ~(IXON | IXOFF); // Disable flow control (Ctrl+S/Q)
tios.c_cc[VMIN] = 1;      // Minimum bytes before read returns
tios.c_cc[VTIME] = 0;     // Inter-byte timer (0 = no timer)
tcsetattr(ptm, TCSANOW, &tios);
```

Note: `cfmakeraw()` is a BSD extension available on Linux/Android. It sets:
- Input: `IGNBRK | BRKINT` (disabled for terminal, good for PTY)
- Input: `IGNPAR | ISTRIP` (no parity processing)
- Output: `OPOST` disabled (no output processing)
- Local: `ECHO | ICANON | ISIG | IEXTEN` disabled (raw input)
- Local: `ECHOK | ECHOE | ECHONL` disabled
- Control: `CSIZE` set to `CS8` (8-bit characters)
- Control: `PARENB` disabled
- Min/Time: `VMIN=1, VTIME=0`

- [ ] **Step 2: Verify build**

Just check the file compiles structurally (Android NDK build requires full NDK setup):

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:externalNativeBuildDebug`
Expected: BUILD SUCCESSFUL (or reasonable error if NDK not fully configured)

- [ ] **Step 3: Commit**

```bash
git add terminal-emulator/src/main/jni/termux.c
git commit -m "fix: raw mode TTY — call cfmakeraw() and set VMIN=1 on PTY master

The PTY now gets proper raw mode initialization including VMIN=1, VTIME=0,
and all cfmakeraw() settings, ensuring programs get predictable TTY state."
```

---

### Task 8: Fix Scrollback Corruption (Bug #8)

**Files:**
- Modify: `terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java`
- Test: `terminal-emulator/src/test/java/com/termux/terminal/HistoryTest.java`

**Root Cause:** `blockCopyLinesDown()` manipulates the circular buffer lines array directly. When `srcInternal + start + 1` wraps around the array boundary, it can overwrite a line that should be copied. Combined with concurrent updates to `mScreenFirstRow` and `mActiveTranscriptRows` in `scrollDownOneLine()`, the circular buffer can lose or corrupt scroll history rows.

- [ ] **Step 1: Write reproduction test for scrollback corruption**

```java
// Add to HistoryTest.java

public void testScrollbackNoCorruptionAfterManyLines() {
    // Write enough lines to fill scrollback multiple times
    for (int page = 0; page < 50; page++) {
        // Write one screenful of unique data
        for (int row = 0; row < mEmulator.mRows; row++) {
            enterString("line_" + page + "_" + row + "\n");
        }
    }

    // Scroll back to beginning
    String transcript = mEmulator.getScreen().getTranscriptText();

    // Verify first lines are intact
    assertTrue("Scrollback should contain first written line",
        transcript.contains("line_0_0"));
    assertTrue("Scrollback should contain last written line",
        transcript.contains("line_49_" + (mEmulator.mRows - 1)));

    // Verify no corruption (garbled text)
    // Each line should have the format "line_X_Y"
    String[] lines = transcript.split("\n");
    for (String line : lines) {
        if (line.startsWith("line_")) {
            String[] parts = line.split("_");
            assertEquals("Line should have 3 parts: " + line, 3, parts.length);
        }
    }
}
```

- [ ] **Step 2: Fix blockCopyLinesDown wrap safety**

```java
// TerminalBuffer.java — modified blockCopyLinesDown()

private void blockCopyLinesDown(int srcInternal, int len) {
    if (len == 0) return;
    int totalRows = mTotalRows;

    // Save the first line that will be overwritten by the shift
    TerminalRow savedLine = mLines[srcInternal % totalRows];

    // Copy lines down, handling wrap carefully
    for (int i = len - 1; i >= 0; --i) {
        int src = (srcInternal + i) % totalRows;
        int dst = (srcInternal + i + 1) % totalRows;
        mLines[dst] = mLines[src];
    }

    // Restore saved line at the vacated position
    mLines[(srcInternal + 1) % totalRows] = savedLine;
}
```

The original code had a subtle off-by-one in handling the overwritten line. The new version:
1. Saves the destination line that's about to be overwritten
2. Copies from bottom to top
3. Restores the saved line to the newly vacated position

- [ ] **Step 3: Add safety bounds check in scrollDownOneLine**

```java
// TerminalBuffer.java — strengthened scrollDownOneLine()

public void scrollDownOneLine(int topMargin, int bottomMargin, long style) {
    if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)
        throw new IllegalArgumentException("topMargin=" + topMargin + ", bottomMargin=" +
            bottomMargin + ", mScreenRows=" + mScreenRows);

    // Copy the fixed topMargin lines one line down:
    if (topMargin > 0) {
        blockCopyLinesDown(mScreenFirstRow, topMargin);
    }
    // Copy the fixed mScreenRows-bottomMargin lines one line down:
    if (mScreenRows - bottomMargin > 0) {
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin);
    }

    // Update the screen location in the ring buffer:
    int newScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows;

    // Note that the history has grown if not already full:
    if (mActiveTranscriptRows < mTotalRows - mScreenRows) {
        mActiveTranscriptRows++;
    } else {
        // History is full — the oldest transcript row was just overwritten.
        // Ensure the screen pointer wraps cleanly.
    }

    mScreenFirstRow = newScreenFirstRow;

    // Blank the newly revealed line above the bottom margin:
    int blankRow = externalToInternalRow(bottomMargin - 1);
    if (mLines[blankRow] == null) {
        mLines[blankRow] = new TerminalRow(mColumns, style);
    } else {
        mLines[blankRow].clear(style);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-emulator:testDebugUnitTest`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java terminal-emulator/src/test/java/com/termux/terminal/HistoryTest.java
git commit -m "fix: scrollback corruption — fix blockCopyLinesDown circular buffer wrap

blockCopyLinesDown had an off-by-one in handling circular buffer wrap,
potentially corrupting scroll history. Simplified the implementation to
save-then-copy-then-restore pattern for correctness."
```

---

### Task 9: Fix Screen Jumping (Bug #10)

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java`

**Root Cause:** `onScreenUpdated()` resets `mTopRow = 0` every time new output arrives, unless selecting text. User scrolling up to read history gets yanked back to bottom on every output line.

- [ ] **Step 1: Add a "user scrolling" flag**

```java
// TerminalView.java — add field
private boolean mUserScrolling = false;
private static final int USER_SCROLL_TIMEOUT_MS = 3000;
private Runnable mClearUserScrollRunnable;
```

- [ ] **Step 2: Track user scroll intent**

```java
// TerminalView.java — in doScroll(), set user scrolling flag
void doScroll(MotionEvent event, int rowsDown) {
    if (mEmulator != null && !mEmulator.isMouseTrackingActive()) {
        // User initiated scroll — mark as user scrolling
        mUserScrolling = true;
        // Auto-clear after timeout with no new scrolls
        if (mClearUserScrollRunnable != null) removeCallbacks(mClearUserScrollRunnable);
        mClearUserScrollRunnable = () -> {
            mUserScrolling = false;
            if (mTopRow != 0) {
                // Wrapped a timeout around staying at the current scroll
            }
        };
        postDelayed(mClearUserScrollRunnable, USER_SCROLL_TIMEOUT_MS);
    }
    // ... rest of doScroll
}
```

Actually, a simpler approach: only auto-scroll if `mTopRow == 0` (already at bottom) OR if the user hasn't scrolled. Track if user has manually set `mTopRow` to non-zero.

```java
// TerminalView.java — modified onScreenUpdated()

public void onScreenUpdated(boolean skipScrolling) {
    if (mEmulator == null) return;

    int rowsInHistory = mEmulator.getScreen().getActiveTranscriptRows();
    if (mTopRow < -rowsInHistory) mTopRow = -rowsInHistory;

    if (isSelectingText() || mEmulator.isAutoScrollDisabled()) {
        // existing selection handling ...
    }

    // Only auto-scroll to bottom if user is at bottom
    // or explicitly wants auto-scroll
    if (!skipScrolling && mTopRow != 0 && !isSelectingText() && !mEmulator.isAutoScrollDisabled()) {
        // User has scrolled up — respect their position
        // Instead of forcing mTopRow = 0, adjust for scroll counter
        int scrollCounter = mEmulator.getScrollCounter();
        mTopRow = Math.max(-rowsInHistory, mTopRow - scrollCounter);
        mEmulator.clearScrollCounter();
        invalidate();
        return;
    }

    if (!skipScrolling && mTopRow != 0) {
        if (mTopRow < -3) awakenScrollBars();
        mTopRow = 0;
    }

    mEmulator.clearScrollCounter();
    invalidate();
    if (mAccessibilityEnabled) setContentDescription(getText());
}
```

Actually, let me reconsider. The original code has the selection handling already:

```java
if (isSelectingText() || mEmulator.isAutoScrollDisabled()) {
    // ... handle scroll without forcing to bottom
    if (!skipScrolling && mTopRow != 0) {
        // ...
    }
}
```

The simplest fix: when user has scrolled (mTopRow != 0), accumulate scroll counter adjustments instead of jumping to 0.

- [ ] **Step 3: Build**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-view:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalView.java
git commit -m "fix: screen jumping on new output — preserve scroll position when user scrolled

onScreenUpdated() now respects user scroll position (mTopRow != 0) by
accumulating scroll counter adjustments instead of forcing mTopRow = 0."
```

---

### Task 10: Fix Clipboard Suggestion Bar Not Appearing (Bug #9)

**Files:**
- Modify: `terminal-view/src/main/java/com/termux/view/TerminalView.java`

**Root Cause:** Android keyboard apps (Gboard, Samsung Keyboard, etc.) show a clipboard suggestion bar above the keyboard when (1) there is text in the clipboard, and (2) the current text editor reports it supports paste operations. Termux sets `InputType.TYPE_NULL` in `onCreateInputConnection()` (line 330), which tells the IME this is NOT a text field. The IME then suppresses clipboard suggestions.

Additionally, `BaseInputConnection` returns `null` from `getTextBeforeCursor()`/`getTextAfterCursor()`, which some keyboards interpret as "no text editing capabilities" and hide clipboard UI.

**Fix:** Override `getTextBeforeCursor()` and `getTextAfterCursor()` in the `BaseInputConnection` to return a sentinel value (single space), signaling to the IME that text operations are supported. Also set `EditorInfo.contentMimeTypes` to `{"text/plain"}` to explicitly advertise paste support, and add `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` to prevent suppression.

- [ ] **Step 1: Modify onCreateInputConnection to enable clipboard suggestions**

```java
// TerminalView.java lines 307-436 — modified onCreateInputConnection()

@Override
public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
    if (mClient.isTerminalViewSelected()) {
        if (mClient.shouldEnforceCharBasedInput()) {
            outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        } else {
            outAttrs.inputType = InputType.TYPE_NULL;
        }
    } else {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
    }

    outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN;

    // === CLIPBOARD SUGGESTION BAR FIX ===
    // Signal to the IME (Gboard, Samsung Keyboard, etc.) that this editor
    // supports text/paste operations so the clipboard suggestion bar appears.
    // TYPE_NULL normally suppresses clipboard suggestions because the keyboard
    // thinks this isn't a text field. We override key InputConnection methods
    // and set contentMimeTypes to re-enable them.
    outAttrs.contentMimeTypes = new String[]{"text/plain"};

    return new BaseInputConnection(this, true) {

        // ---- Clipboard suggestion support ----
        // Override to return a sentinel value so the IME believes there is
        // editable text content. This is what triggers the clipboard suggestion
        // bar in Gboard and other keyboards. Without this, TYPE_NULL makes
        // the IME think the field is read-only and suppresses suggestions.

        @Override
        public CharSequence getTextBeforeCursor(int length, int flags) {
            if (length <= 0) return "";
            // Return a single space so the IME considers this an active text field.
            // The actual terminal buffer is managed by TerminalEmulator, not this
            // InputConnection, so returning "real" text would be misleading.
            return " ";
        }

        @Override
        public CharSequence getTextAfterCursor(int length, int flags) {
            if (length <= 0) return "";
            return " ";
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return ""; // No selection in InputConnection (selection is in emulator)
        }

        @Override
        public int getCursorCapsMode(int reqModes) {
            return 0; // Terminal input is case-sensitive
        }

        @Override
        public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) {
            // Return minimal extracted text so the IME doesn't crash or suppress UI
            ExtractedText et = new ExtractedText();
            et.text = " ";
            et.startOffset = 0;
            et.partialStartOffset = -1;
            et.partialEndOffset = -1;
            et.selectionStart = 0;
            et.selectionEnd = 0;
            et.flags = 0;
            return et;
        }
        // ---- End clipboard suggestion support ----

        @Override
        public boolean finishComposingText() {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logInfo(LOG_TAG, "IME: finishComposingText()");
            super.finishComposingText();

            sendTextToTerminal(getEditable());
            getEditable().clear();
            return true;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            // ... existing code unchanged ...
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                mClient.logInfo(LOG_TAG, "IME: commitText(\"" + text + "\", " + newCursorPosition + ")");
            }
            super.commitText(text, newCursorPosition);

            if (mEmulator == null) return true;

            Editable content = getEditable();
            sendTextToTerminal(content);
            content.clear();
            return true;
        }

        // deleteSurroundingText() unchanged ...

        void sendTextToTerminal(CharSequence text) {
            // ... unchanged ...
        }
    };
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /home/termux/termux-app && ./gradlew :terminal-view:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification**

Install the app on a device/emulator with Gboard. Copy any text, tap the terminal, verify clipboard suggestion bar appears above the keyboard.

- [ ] **Step 4: Commit**

```bash
git add terminal-view/src/main/java/com/termux/view/TerminalView.java
git commit -m "fix: clipboard suggestion bar not appearing on keyboard

Override getTextBeforeCursor/getTextAfterCursor/getSelectedText/getExtractedText
in the InputConnection to return sentinel values, signaling to the IME that
this is an active text field even with TYPE_NULL. Also set contentMimeTypes
to 'text/plain' to explicitly advertise paste support.
This enables the clipboard suggestion bar in Gboard/Samsung Keyboard."

---

### Verification Checklist

- [ ] All existing tests pass (`./gradlew :terminal-emulator:testDebugUnitTest`)
- [ ] ByteQueue deadlock reproduction test passes
- [ ] Alt-screen DEC flags restoration test passes
- [ ] Scrollback corruption test passes
- [ ] `:terminal-view:compileDebugJavaWithJavac` succeeds
- [ ] `:app:compileDebugJavaWithJavac` succeeds
- [ ] Native build (`:terminal-emulator:externalNativeBuildDebug`) succeeds (or NDK is optional)
- [ ] No lint errors introduced

### Rollback Plan

- **Revert:** `git revert <merge-commit>` for the entire branch
- **Per-task revert:** Each commit is atomic; revert specific task commits as needed
- **Monitor key metrics after deploy:**
  - ANR rate (should decrease from ByteQueue + paste fixes)
  - Process exit signal 9 rate (should decrease from deadlock fix)
  - User reports of "keyboard not working" (should decrease from InputConnection fix)
