package com.texmux.api;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.texmux.api.util.ApiRequest;
import com.texmux.api.util.ResultReturner;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * JNI bridge to {@code libtermux-api.so}.
 *
 * <p>Provides Java with direct access to C utility functions (UUID,
 * JSON escaping, Base64, JSON command building) and an end-to-end
 * {@link #process} path that bypasses the socket entirely for
 * in-process API calls.</p>
 *
 * <p>Unlike the C binary path (which connects to the socket), this
 * class goes directly to {@link DirectDispatcher} — no socket, no
 * {@code am broadcast}, no back-connect.  The C library handles
 * argument parsing; Java handles dispatch and result formatting.</p>
 */
public final class NativeBridge {

    static {
        System.loadLibrary("termux-api");
    }

    private NativeBridge() {
        // Utility class — no instances
    }

    // ─── Native methods (implemented in termux-api-jni.c) ──────────

    /** Generate a UUID v4-like string (wraps generate_uuid()). */
    public static native String generateUuid();

    /** JSON-escape a string (wraps json_escape()). */
    public static native String jsonEscape(String input);

    /** Base64-encode a byte array (wraps b64_encode()). */
    public static native String b64Encode(byte[] data);

    /**
     * Build a JSON command from shell-style arguments.
     * Equivalent to what the C binary builds from {@code argv}.
     *
     * @param args e.g. {@code ["BatteryStatus"]} or
     *             {@code ["Sensor", "--es", "-s", "Accelerometer", "--ei", "-n", "1"]}
     * @return the JSON command string, or empty string on failure
     */
    public static native String buildJsonCommand(String[] args);

    /**
     * Create a Unix domain socket on the filesystem (not abstract namespace),
     * bind it, listen on it, and return the {@link FileDescriptor}.
     *
     * <p>Filesystem sockets work inside proot where SELinux blocks abstract
     * namespace sockets. After obtaining the FD, wrap it with
     * {@code LocalServerSocket(FileDescriptor)}.</p>
     *
     * @param path absolute filesystem path for the socket (e.g.
     *             /data/data/com.texmux/files/termux-api.sock)
     * @return FileDescriptor ready for accept(), or null on failure
     */
    public static native FileDescriptor nativeCreateFileServerSocket(String path);

    // ─── High-level: in-process dispatch (no socket) ───────────────

    private static final TermuxApiReceiver directReceiver = new TermuxApiReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { }
    };

    /**
     * Process an API command entirely in-process — no socket, no IPC.
     *
     * <p>This is the Java equivalent of running the {@code termux-api}
     * C binary, but everything happens inside the app process:</p>
     * <ol>
     *   <li>Builds the JSON command from {@code args} (via JNI)</li>
     *   <li>Parses the JSON into an {@link ApiRequest}</li>
     *   <li>Dispatches to the appropriate API handler via
     *       {@link DirectDispatcher}</li>
     *   <li>Captures the handler's output and returns it</li>
     * </ol>
     *
     * @param context Android context for system services
     * @param args    shell-style command arguments
     * @return the output that would be written to stdout, or
     *         {@code null} if the command could not be processed
     */
    @Nullable
    public static String process(@NonNull Context context, @NonNull String[] args) {
        String json = buildJsonCommand(args);
        if (json.isEmpty()) return null;

        ApiRequest request;
        try {
            request = ApiRequest.fromJson(json);
        } catch (Exception e) {
            return null;
        }

        Intent intent = new Intent();
        request.populateIntent(intent);
        intent.putExtra("api_method", request.method);

        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        OutputStream prev = ResultReturner.setDirectResponseStream(baos);
        try {
            DirectDispatcher.dispatch(directReceiver, context, intent);
        } finally {
            ResultReturner.setDirectResponseStream(prev);
        }

        String result = baos.toString(StandardCharsets.UTF_8);
        return result.isEmpty() ? null : result;
    }
}
