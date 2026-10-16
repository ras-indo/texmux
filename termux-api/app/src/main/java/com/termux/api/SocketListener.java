package com.termux.api;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import com.termux.api.util.ApiRequest;
import com.termux.api.util.ResultReturner;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import android.util.Base64;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * Listens for Termux API commands on an abstract-namespace Unix socket.
 *
 * <h3>Protocol detection</h3>
 * <p>The listener supports two wire formats, auto-detected from the first
 * byte of the decoded command string:</p>
 * <ul>
 *   <li><b>JSON</b> (first byte = <code>{</code>): parsed via
 *       {@link ApiRequest}, dispatched directly, response written on
 *       the same connection.  No {@code socket_input}/{@code socket_output}
 *       extras, no {@code ResultReturner} back-connect.</li>
 *   <li><b>Legacy</b> (first byte = anything else): the original 7-regex
 *       {@code --es/--ez/--ei} protocol with I/O sockets and 0x00
 *       acknowledgment.  Kept for backward compatibility with the
 *       C binary ({@code termux-api-broadcast}).</li>
 * </ul>
 *
 * <h3>Threading</h3>
 * <p>A cached thread pool handles accepted connections concurrently, so
 * a slow API call ({@code Location}) does not block other callers.</p>
 */
public class SocketListener {

    public static final String LISTEN_ADDRESS = TermuxConstants.TERMUX_API_PACKAGE_NAME + "://listen";

    // Fallback filesystem socket — created via JNI (LocalServerSocket only
    // supports abstract namespace).  The C binary tries abstract first,
    // then this filesystem path, then legacy am broadcast.
    public static final String FILE_SOCKET_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/termux-api.sock";

    // Daemon socket — entry point for nc -U fast path (no C binary fork).
    // Located at the app data root so scripts use an absolute path.
    public static final String DAEMON_SOCKET_PATH = TermuxConstants.TERMUX_FILES_DIR_PATH + "/api-daemon.sock";

    // ─── Legacy regex patterns (unchanged) ─────────────────────────────
    private static final Pattern EXTRA_STRING = Pattern.compile("(-e|--es|--esa) +([^ ]+) +\"(.*?)(?<!\\\\)\"", Pattern.DOTALL);
    private static final Pattern EXTRA_BOOLEAN = Pattern.compile("--ez +([^ ]+) +([^ ]+)");
    private static final Pattern EXTRA_INT = Pattern.compile("--ei +([^ ]+) +(-?[0-9]+)");
    private static final Pattern EXTRA_FLOAT = Pattern.compile("--ef +([^ ]+) +(-?[0-9]+(?:\\.[0-9]+))");
    private static final Pattern EXTRA_INT_LIST = Pattern.compile("--eia +([^ ]+) +(-?[0-9]+(?:,-?[0-9]+)*)");
    private static final Pattern EXTRA_LONG_LIST = Pattern.compile("--ela +([^ ]+) +(-?[0-9]+(?:,-?[0-9]+)*)");
    private static final Pattern EXTRA_UNSUPPORTED = Pattern.compile("--e[^izs ] +[^ ]+ +[^ ]+");
    private static final Pattern ACTION = Pattern.compile("-a *([^ ]+)");

    private static Thread listener = null;
    private static Thread fileSocketListener = null;
    private static ExecutorService threadPool = null;
    private static final String LOG_TAG = "SocketListener";

    /**
     * Lightweight receiver instance used for legacy dispatch.
     * {@code isOrderedBroadcast()} returns false, so
     * {@link ResultReturner} skips the {@code goAsync()} path.
     */
    private static final TermuxApiReceiver directReceiver = new TermuxApiReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { }
    };

    // ─── Bootstrap ────────────────────────────────────────────────────

    public static void createSocketListener(Application app) {
        if (listener != null) return;

        // Shared thread pool for all API connections.
        // CachedThreadPool: unbounded, idle threads die after 60 s.
        threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "api-handler");
            t.setDaemon(true);
            return t;
        });

        listener = new Thread(() -> {
            try (LocalServerSocket listen = new LocalServerSocket(LISTEN_ADDRESS)) {
                Logger.logInfo(LOG_TAG, "Listening on " + LISTEN_ADDRESS);
                while (true) {
                    LocalSocket con = listen.accept();
                    // UID check intentionally skipped: the C binary runs inside
                    // the proot container (UID may differ from the app process),
                    // while the SocketListener lives in the main app process.
                    // The abstract socket address is globally unique, so no
                    // other listener can impersonate it.
                    // Hand off to thread pool — never block accept()
                    threadPool.submit(() -> handleConnection(con, app));
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Fatal listener error", e);
            }
        }, "socket-listener");
        listener.setDaemon(true);
        listener.start();

        // ── Filesystem socket (SELinux-safe fallback for proot) ──────────
        // LocalServerSocket(String) only creates abstract-namespace sockets.
        // We use a JNI native method to create a proper filesystem Unix socket,
        // then wrap the fd with LocalServerSocket(FileDescriptor).
        new File(FILE_SOCKET_PATH).delete();
        fileSocketListener = new Thread(() -> {
            try {
                java.io.FileDescriptor fd = NativeBridge.nativeCreateFileServerSocket(FILE_SOCKET_PATH);
                if (fd == null) {
                    Logger.logError(LOG_TAG, "Failed to create filesystem socket at " + FILE_SOCKET_PATH);
                    return;
                }
                try (LocalServerSocket listen = new LocalServerSocket(fd)) {
                    Logger.logInfo(LOG_TAG, "Listening on " + FILE_SOCKET_PATH);
                    while (true) {
                        LocalSocket con = listen.accept();
                        threadPool.submit(() -> handleConnection(con, app));
                    }
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Filesystem socket listener error (" + FILE_SOCKET_PATH + ")", e);
            } finally {
                new File(FILE_SOCKET_PATH).delete();
            }
        }, "file-socket-listener");
        fileSocketListener.setDaemon(true);
        fileSocketListener.start();

        // ── Daemon socket (nc -U fast path — no C binary needed) ──────
        new File(DAEMON_SOCKET_PATH).delete();
        Thread daemonListener = new Thread(() -> {
            try {
                java.io.FileDescriptor fd = NativeBridge.nativeCreateFileServerSocket(DAEMON_SOCKET_PATH);
                if (fd == null) {
                    Logger.logError(LOG_TAG, "Failed to create daemon socket at " + DAEMON_SOCKET_PATH);
                    return;
                }
                try (LocalServerSocket listen = new LocalServerSocket(fd)) {
                    Logger.logInfo(LOG_TAG, "Daemon listening on " + DAEMON_SOCKET_PATH);
                    while (true) {
                        LocalSocket con = listen.accept();
                        threadPool.submit(() -> handleConnection(con, app));
                    }
                }
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Daemon socket error (" + DAEMON_SOCKET_PATH + ")", e);
            } finally {
                new File(DAEMON_SOCKET_PATH).delete();
            }
        }, "daemon-socket-listener");
        daemonListener.setDaemon(true);
        daemonListener.start();
    }

    // ─── Per-connection handler ────────────────────────────────────────

    private static void handleConnection(LocalSocket con, Application app) {
        try (con) {
            DataInputStream in = new DataInputStream(con.getInputStream());
            int length = in.readUnsignedShort();
            byte[] raw = new byte[length];
            in.readFully(raw);
            String cmdline = new String(raw, StandardCharsets.UTF_8);

            // Auto-detect protocol from first byte
            if (!cmdline.isEmpty() && cmdline.charAt(0) == '{') {
                handleJsonProtocol(con, app, cmdline, raw);
            } else {
                handleLegacyProtocol(con, app, cmdline, raw);
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Connection error", e);
        }
    }

    // ─── JSON protocol (bidirectional, with async fallback) ────────────

    private static void handleJsonProtocol(LocalSocket con, Application app,
                                            String cmdline, byte[] raw) {
        try {
            ApiRequest request = ApiRequest.fromJson(cmdline);
            Intent intent = new Intent();
            request.populateIntent(intent);

            OutputStream conOut = con.getOutputStream();
            ResultReturner.setDirectResponseStream(conOut);

            // Handlers like UsbAPI use startService() — async. By the time
            // the Service runs, the per-thread directResponseStream is gone.
            // Inject socket addresses so the Service can back-connect.
            String fbOutputAddr = UUID.randomUUID().toString();
            String fbInputAddr  = UUID.randomUUID().toString();
            intent.putExtra("socket_output", fbOutputAddr);
            intent.putExtra("socket_input",  fbInputAddr);

            LocalServerSocket fbOutputServer = null;
            LocalServerSocket fbInputServer  = null;
            try {
                fbOutputServer = new LocalServerSocket(fbOutputAddr);
                fbInputServer  = new LocalServerSocket(fbInputAddr);
            } catch (IOException e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Failed to create fallback sockets for async handler", e);
            }

            JSONObject root = new JSONObject(cmdline);
            String stdinB64 = root.optString("stdin", null);
            InputStream directIn = null;
            if (stdinB64 != null && !stdinB64.isEmpty()) {
                byte[] stdinBytes = Base64.decode(stdinB64, Base64.DEFAULT);
                directIn = new ByteArrayInputStream(stdinBytes);
                ResultReturner.setDirectInputStream(directIn);
            }

            AtomicBoolean responded = new AtomicBoolean(false);
            ResultReturner.setDirectResponseHook(() -> responded.set(true));

            try {
                DirectDispatcher.dispatch(directReceiver, app.getApplicationContext(), intent);

                if (!responded.get() && fbOutputServer != null) {
                    if (awaitAsyncResponse(fbOutputServer, fbInputServer,
                                           conOut, request.correlationId)) {
                        responded.set(true);
                    }
                }
            } finally {
                ResultReturner.clearDirectResponseStream();
                if (directIn != null) {
                    ResultReturner.clearDirectInputStream();
                    try { directIn.close(); } catch (IOException ignored) {}
                }
                if (fbOutputServer != null) {
                    try { fbOutputServer.close(); } catch (IOException ignored) {}
                }
                if (fbInputServer != null) {
                    try { fbInputServer.close(); } catch (IOException ignored) {}
                }

                if (!responded.get()) {
                    String errJson = "{\"correlationId\":\""
                        + escapeJson(request.correlationId)
                        + "\",\"success\":false,\"error\":\"Handler did not produce a response\"}\n";
                    conOut.write(errJson.getBytes(StandardCharsets.UTF_8));
                    conOut.flush();
                }
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error handling JSON request", e);
        }
    }

    /** @return true if a response was received via the fallback socket. */
    private static boolean awaitAsyncResponse(
            LocalServerSocket outputServer,
            LocalServerSocket inputServer,
            OutputStream conOut,
            String correlationId) {

        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-fallback");
            t.setDaemon(true);
            return t;
        });

        try {
            Future<Boolean> future = worker.submit(() -> {
                try (LocalSocket s = outputServer.accept()) {
                    Logger.logDebug(LOG_TAG,
                        "Async handler connected via fallback socket");
                    InputStream fromHandler = s.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fromHandler.read(buf)) >= 0) {
                        conOut.write(buf, 0, n);
                        conOut.flush();
                    }
                }
                return true;
            });

            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Logger.logDebug(LOG_TAG,
                "Async handler did not respond within 30s timeout");
            return false;
        } catch (ExecutionException e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                "Error in async fallback accept", e.getCause());
            return false;
        } catch (InterruptedException e) {
            Logger.logDebug(LOG_TAG, "Async fallback interrupted");
            Thread.currentThread().interrupt();
            return false;
        } finally {
            worker.shutdownNow();
        }
    }

    // ─── Legacy protocol (--es/--ez/--ei, I/O sockets, 0x00 ack) ─────

    @SuppressWarnings("StringBufferReplaceableByString")
    private static void handleLegacyProtocol(LocalSocket con, Application app,
                                              String cmdline, byte[] raw) {
        try (BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(con.getOutputStream()))) {

            Intent intent = new Intent();

            // ── 7-regex parsing (unchanged from original) ──────────────
            HashMap<String, String> stringExtras = new HashMap<>();
            HashMap<String, String[]> stringArrayExtras = new HashMap<>();
            HashMap<String, Boolean> booleanExtras = new HashMap<>();
            HashMap<String, Integer> intExtras = new HashMap<>();
            HashMap<String, Float> floatExtras = new HashMap<>();
            HashMap<String, int[]> intArrayExtras = new HashMap<>();
            HashMap<String, long[]> longArrayExtras = new HashMap<>();
            boolean err = false;

            Matcher m = EXTRA_STRING.matcher(cmdline);
            while (m.find()) {
                String option = m.group(1);
                if ("-e".equals(option) || "--es".equals(option)) {
                    stringExtras.put(m.group(2), Objects.requireNonNull(m.group(3)).replaceAll("\\\\\"", "\""));
                } else {
                    String[] list = Objects.requireNonNull(m.group(3)).split("(?<!\\\\),");
                    for (int i = 0; i < list.length; i++) {
                        list[i] = list[i].replaceFirst("\\\\,", ",");
                    }
                    stringArrayExtras.put(m.group(2), list);
                }
            }
            cmdline = m.replaceAll("");

            m = EXTRA_BOOLEAN.matcher(cmdline);
            while (m.find()) {
                String value = m.group(2);
                value = value != null ? value.toLowerCase() : null;
                Boolean arg = null;
                if ("true".equals(value) || "t".equals(value)) {
                    arg = true;
                } else if ("false".equals(value) || "f".equals(value)) {
                    arg = false;
                } else {
                    try {
                        if (value != null) arg = Integer.decode(value) != 0;
                    } catch (NumberFormatException ignored) {}
                }
                if (arg == null) {
                    out.write("Invalid boolean extra: " + m.group(0) + "\n");
                    err = true;
                    break;
                }
                booleanExtras.put(m.group(1), arg);
            }
            cmdline = m.replaceAll("");

            m = EXTRA_INT.matcher(cmdline);
            while (m.find()) {
                try {
                    intExtras.put(m.group(1), Integer.parseInt(Objects.requireNonNull(m.group(2))));
                } catch (NumberFormatException e) {
                    out.write("Invalid integer extra: " + m.group(0) + "\n");
                    err = true;
                    break;
                }
            }
            cmdline = m.replaceAll("");

            m = EXTRA_FLOAT.matcher(cmdline);
            while (m.find()) {
                try {
                    floatExtras.put(m.group(1), Float.parseFloat(Objects.requireNonNull(m.group(2))));
                } catch (NumberFormatException e) {
                    out.write("Invalid float extra: " + m.group(0) + "\n");
                    err = true;
                    break;
                }
            }
            cmdline = m.replaceAll("");

            m = EXTRA_INT_LIST.matcher(cmdline);
            while (m.find()) {
                try {
                    String[] parts = Objects.requireNonNull(m.group(2)).split(",");
                    int[] ints = new int[parts.length];
                    for (int i = 0; i < parts.length; i++) ints[i] = Integer.parseInt(parts[i]);
                    intArrayExtras.put(m.group(1), ints);
                } catch (NumberFormatException e) {
                    out.write("Invalid int array extra: " + m.group(0) + "\n");
                    err = true;
                    break;
                }
            }
            cmdline = m.replaceAll("");

            m = EXTRA_LONG_LIST.matcher(cmdline);
            while (m.find()) {
                try {
                    String[] parts = Objects.requireNonNull(m.group(2)).split(",");
                    long[] longs = new long[parts.length];
                    for (int i = 0; i < parts.length; i++) longs[i] = Long.parseLong(parts[i]);
                    longArrayExtras.put(m.group(1), longs);
                } catch (NumberFormatException e) {
                    out.write("Invalid long array extra: " + m.group(0) + "\n");
                    err = true;
                    break;
                }
            }
            cmdline = m.replaceAll("");

            m = ACTION.matcher(cmdline);
            while (m.find()) {
                intent.setAction(m.group(1));
            }
            cmdline = m.replaceAll("");

            m = EXTRA_UNSUPPORTED.matcher(cmdline);
            if (m.find()) {
                out.write("Unsupported argument type: " + m.group(0) + "\n");
                err = true;
            }
            cmdline = m.replaceAll("");

            cmdline = cmdline.replaceAll("\\s", "");
            if (!"".equals(cmdline)) {
                out.write("Unsupported options: " + cmdline + "\n");
                err = true;
            }

            if (err) {
                out.flush();
                return;
            }

            for (Map.Entry<String, String> e : stringExtras.entrySet())
                intent.putExtra(e.getKey(), e.getValue());
            for (Map.Entry<String, String[]> e : stringArrayExtras.entrySet())
                intent.putExtra(e.getKey(), e.getValue());
            for (Map.Entry<String, Integer> e : intExtras.entrySet())
                intent.putExtra(e.getKey(), e.getValue());
            for (Map.Entry<String, Boolean> e : booleanExtras.entrySet())
                intent.putExtra(e.getKey(), e.getValue());
            for (Map.Entry<String, Float> e : floatExtras.entrySet())
                intent.putExtra(e.getKey(), e.getValue());
            for (Map.Entry<String, int[]> e : intArrayExtras.entrySet())
                intent.putExtra(e.getKey(), e.getValue());
            for (Map.Entry<String, long[]> e : longArrayExtras.entrySet())
                intent.putExtra(e.getKey(), e.getValue());

            try {
                DirectDispatcher.dispatch(directReceiver, app.getApplicationContext(), intent);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Error dispatching", e);
            } finally {
                try {
                    con.getOutputStream().write(0);
                    con.getOutputStream().flush();
                } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Connection error", e);
        }
    }

    // ─── JSON escape helper ────────────────────────────────────────────

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
