package texmux.terminal;

import java.nio.charset.StandardCharsets;

/** A client which receives callbacks from events triggered by feeding input to a {@link TerminalEmulator}. */
public abstract class TerminalOutput {

    /** Write a string using the UTF-8 encoding to the terminal client. */
    public final void write(String data) {
        if (data == null) return;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        write(bytes, 0, bytes.length);
    }

    /** Write bytes to the terminal client. */
    public abstract void write(byte[] data, int offset, int count);

    /** Notify the terminal client that the terminal title has changed. */
    public abstract void titleChanged(String oldTitle, String newTitle);

    /**
     * Write bytes to the process in chunks to prevent ANR on large pastes.
     * Default implementation performs a single write. Override in TerminalSession
     * for proper chunked behavior.
     */
    public void pasteBytes(byte[] data) {
        write(data, 0, data.length);
    }

    /** Notify the terminal client that text should be copied to clipboard. */
    public abstract void onCopyTextToClipboard(String text);

    /** Notify the terminal client that text should be pasted from clipboard. */
    public abstract void onPasteTextFromClipboard();

    /** Notify the terminal client that a bell character (ASCII 7, bell, BEL, \a, ^G)) has been received. */
    public abstract void onBell();

    public abstract void onColorsChanged();

    /**
     * Receive an Operating System Command (OSC) 7 working directory report.
     * Default implementation does nothing; override to handle CWD tracking.
     */
    public void onOsc7Cwd(@SuppressWarnings("unused") String cwdUrl) {
    }

}
