package texmux.api;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import texmux.api.util.ApiRequest;
import texmux.api.util.ResultReturner;
import texmux.shared.logger.Logger;
import texmux.shared.termux.TermuxConstants;

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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
                    submitConnection(con, app);
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
                        submitConnection(con, app);
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
                        submitConnection(con, app);
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

    /**
     * Hands a connection to the pool without losing a throwable.
     *
     * <p>{@code submit()} returns a {@link Future} nobody reads, so anything that
     * escapes {@link #handleConnection} - an Error, or a RuntimeException from
     * our own code - was discarded without a log line. That is the quietest way
     * to lose a bug: the client just saw the socket close.</p>
     */
    private static void submitConnection(LocalSocket con, Application app) {
        threadPool.submit(() -> {
            try {
                handleConnection(con, app);
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                        "Unhandled throwable in connection handler", t);
                try {
                    con.close();
                } catch (IOException ignored) { }
            }
        });
    }

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
            // No request was decoded, so there is no command name to report yet.
            Logger.logStackTraceWithMessage(LOG_TAG, "Connection error (request could not be read)", e);
        }
    }

    // ─── JSON protocol (bidirectional, with async fallback) ────────────

    /** How long an async handler (a Service back-connecting) may take to answer. */
    private static final int ASYNC_TIMEOUT_SECONDS = 30;

    private static final String NO_OUTPUT_HINT =
            "No result was written for this command. Either the handler returned without calling "
            + "ResultReturner.returnData(), or the method name is unknown to DirectDispatcher. "
            + "Run 'logcat -s SocketListener ResultReturner DirectDispatcher', repeat the command and report it.";

    private static final String THROW_HINT =
            "The handler threw before answering; the full stack trace is in logcat under 'SocketListener'. "
            + "This is a bug in Termux:API, not in the calling script.";

    private static final String INVALID_REQUEST_HINT =
            "A request is a JSON object with a non-empty \"method\" field, e.g. {\"method\":\"BatteryStatus\"}.";

    private static final String WRITE_FAILURE_HINT =
            "The command ran but its result never reached this socket. Run "
            + "'logcat -s ResultReturner' and retry once.";

    // Tolerant readers for the two fields an error message needs even when the
    // request does not parse: both are plain string members of the root object.
    private static final Pattern JSON_METHOD =
            Pattern.compile("\"method\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JSON_CORRELATION_ID =
            Pattern.compile("\"correlationId\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static void handleJsonProtocol(LocalSocket con, Application app,
                                            String cmdline, byte[] raw) {
        ResponseGate gate = null;
        String command = null;
        String correlationId = null;
        try {
            gate = new ResponseGate(con.getOutputStream());

            // Read the identifying fields straight out of the text: they have to
            // survive so that a request which fails to parse can still name itself.
            command = peekJsonString(JSON_METHOD, cmdline);
            correlationId = peekJsonString(JSON_CORRELATION_ID, cmdline);

            final ApiRequest request;
            try {
                request = ApiRequest.fromJson(cmdline);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                        "Unparseable JSON request (command=" + command + ")", e);
                gate.fail(correlationId, command,
                        withCommand(command, "invalid request JSON: " + ResultReturner.describeThrowable(e)),
                        INVALID_REQUEST_HINT, ResultReturner.exceptionClassName(e));
                return;
            }

            if (request.method != null && !request.method.isEmpty()) command = request.method;
            if (request.correlationId != null && !request.correlationId.isEmpty())
                correlationId = request.correlationId;

            serveJsonRequest(app, gate, request, cmdline, command, correlationId);
        } catch (Throwable t) {
            // Whatever escaped, name it. This used to be logged and dropped, and
            // the client was told only that "the handler did not produce a response".
            Logger.logStackTraceWithMessage(LOG_TAG,
                    "JSON request failed outside the handler (command=" + command + ")", t);
            if (gate != null) {
                gate.fail(correlationId, command,
                        withCommand(command, "request failed before the handler answered: "
                                + ResultReturner.describeThrowable(t)),
                        THROW_HINT, ResultReturner.exceptionClassName(t));
            }
        } finally {
            // Cleared here as well as in serveJsonRequest: an escape before its own
            // try block must not leave a pooled thread pointed at a dead socket.
            ResultReturner.clearDirectResponseStream();
            ResultReturner.clearDirectResponseHook();
            ResultReturner.clearDirectInputStream();
            ResultReturner.clearDirectWriteFailure();
            if (gate != null) gate.flushQuietly();
        }
    }

    /**
     * Dispatches one parsed request and guarantees the client gets exactly one
     * answer: the handler's own, or an error that names this command.
     */
    private static void serveJsonRequest(Application app, ResponseGate gate, ApiRequest request,
                                         String cmdline, String command, String correlationId) {
        Intent intent = new Intent();
        request.populateIntent(intent);

        // Streaming APIs (Sensor, Location, MicRecorder, SpeechToText,
        // TextToSpeech, NotificationList) put their own socket_output in the
        // request: the client is already listening on that address and it
        // never reads this connection. Overwriting it - which the fallback
        // injection below used to do unconditionally - pointed the handler
        // at a socket the client knows nothing about, so the client sat
        // there until its 30 s timeout and printed "no response within 30 s
        // (streaming connection never arrived)" while the handler happily
        // wrote into our private server.
        //
        // The address has to arrive inside "args", because ApiRequest.fromJson()
        // mirrors only that object to intent extras. termux-api.c used to append
        // it to the JSON root, where fromJson() dropped it - the request then
        // looked non-streaming and took the fallback path above, which is the
        // same 30 s hang from the other side. Accept a root-level key too so a
        // client binary older than this app build still streams.
        JSONObject root;
        try {
            root = new JSONObject(cmdline);
        } catch (Exception e) {
            // Unreachable in practice - ApiRequest.fromJson already parsed this text -
            // but JSONException is checked and must not become an unhandled one.
            gate.fail(correlationId, command,
                    withCommand(command, "invalid request JSON: " + ResultReturner.describeThrowable(e)),
                    INVALID_REQUEST_HINT, ResultReturner.exceptionClassName(e));
            return;
        }
        String clientOutputAddr = intent.getStringExtra("socket_output");
        if (clientOutputAddr == null || clientOutputAddr.isEmpty()) {
            String rootOutputAddr = root.optString("socket_output", null);
            if (rootOutputAddr != null && !rootOutputAddr.isEmpty()) {
                clientOutputAddr = rootOutputAddr;
                intent.putExtra("socket_output", rootOutputAddr);
                Logger.logDebug(LOG_TAG,
                    "socket_output came from the JSON root instead of args (old client build), using it");
            }
        }
        final boolean clientStreams = clientOutputAddr != null && !clientOutputAddr.isEmpty();

        if (clientStreams) {
            Logger.logDebug(LOG_TAG, "Streaming request: keeping client socket_output");
            // The handler writes straight into the client's socket, where this
            // connection's gate cannot see it. Without these extras a throw
            // inside ResultReturner left the client with a closed socket and
            // zero bytes - indistinguishable from the old 30 s hang. Ask for a
            // JSON error line on the client's own stream instead.
            intent.putExtra(ResultReturner.EXTRA_JSON_ERRORS, true);
            intent.putExtra(ResultReturner.EXTRA_CORRELATION_ID, correlationId);
        } else {
            // The handler writes through the gate, which is what makes "already
            // answered" knowable and a second write impossible.
            ResultReturner.setDirectResponseStream(gate);
            ResultReturner.clearDirectWriteFailure();
            ResultReturner.setDirectResponseHook(gate::markDelivered);
        }

        String stdinB64 = root.optString("stdin", null);
        InputStream directIn = null;
        if (stdinB64 != null && !stdinB64.isEmpty()) {
            byte[] stdinBytes = Base64.decode(stdinB64, Base64.DEFAULT);
            directIn = new ByteArrayInputStream(stdinBytes);
            ResultReturner.setDirectInputStream(directIn);
        }

        // Handlers like UsbAPI use startService() — async. By the time
        // the Service runs, the per-thread directResponseStream is gone.
        // Inject socket addresses so the Service can back-connect. Only for
        // non-streaming requests: a streaming client owns both addresses.
        String fbOutputAddr = null;
        String fbInputAddr  = null;
        LocalServerSocket fbOutputServer = null;
        LocalServerSocket fbInputServer  = null;
        String fallbackFailure = null;
        if (!clientStreams) {
            fbOutputAddr = UUID.randomUUID().toString();
            fbInputAddr  = UUID.randomUUID().toString();
            intent.putExtra("socket_output", fbOutputAddr);
            intent.putExtra("socket_input",  fbInputAddr);
            // A handler answering later from a Service has no thread-local stream:
            // ask ResultReturner to answer with JSON if it throws, or the client
            // reads zero bytes and cannot tell a crash from a hang.
            intent.putExtra(ResultReturner.EXTRA_JSON_ERRORS, true);
            intent.putExtra(ResultReturner.EXTRA_CORRELATION_ID, correlationId);

            try {
                fbOutputServer = new LocalServerSocket(fbOutputAddr);
                fbInputServer  = new LocalServerSocket(fbInputAddr);
            } catch (IOException e) {
                fallbackFailure = "the result socket for the handler could not be created: "
                        + ResultReturner.describeThrowable(e);
                Logger.logStackTraceWithMessage(LOG_TAG,
                        "Failed to create fallback sockets for async handler (" + command + ")", e);
            }
        }

        try {
            DirectDispatcher.dispatch(directReceiver, app.getApplicationContext(), intent);

            if (!clientStreams && !gate.delivered()) {
                // A failed write is known right now: make the client wait 30 s only
                // when the handler may still answer from a Service.
                ResultReturner.Failure writeFailure = ResultReturner.getDirectWriteFailure();
                if (writeFailure != null) {
                    gate.fail(correlationId, command,
                            withCommand(command, "no response was produced: "
                                    + writeFailure.describe()),
                            WRITE_FAILURE_HINT, writeFailure.exceptionClass);
                } else if (fbOutputServer != null) {
                    awaitAsyncResponse(fbOutputServer, fbInputServer, gate, command, correlationId);
                }
            }
        } catch (Throwable t) {
            // The handler threw before writing: report what threw, not a shrug.
            Logger.logStackTraceWithMessage(LOG_TAG,
                    "Handler threw (command=" + command + ")", t);
            gate.fail(correlationId, command,
                    withCommand(command, "handler threw " + ResultReturner.describeThrowable(t)),
                    THROW_HINT, ResultReturner.exceptionClassName(t));
        } finally {
            ResultReturner.clearDirectResponseStream();
            ResultReturner.clearDirectResponseHook();
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

            // Streaming requests never expect an answer on this connection:
            // their data goes to the socket_output the client owns.
            //
            // Normally one of the branches above has already answered and this is a
            // no-op, so it stays as a last resort rather than a second response.
            if (!clientStreams && !gate.delivered()) {
                if (fallbackFailure != null) {
                    gate.fail(correlationId, command,
                            withCommand(command, fallbackFailure),
                            "The handler may have written to a socket that was never created, "
                            + "so its result was lost.",
                            null);
                } else {
                    gate.fail(correlationId, command,
                            withCommand(command, "handler produced no output "
                                    + "(no result was written to the socket)"),
                            NO_OUTPUT_HINT, null);
                }
            }
            ResultReturner.clearDirectWriteFailure();
            gate.flushQuietly();
        }
    }

    /**
     * Waits for an async handler (a Service) to connect to the fallback socket and
     * copies its answer to the client.
     *
     * <p>Every way out of here either copies bytes or writes a JSON error naming
     * the command and, for the timeout, how long it waited.</p>
     *
     * @return true if the answer reached the client.
     */
    private static boolean awaitAsyncResponse(
            LocalServerSocket outputServer,
            LocalServerSocket inputServer,
            ResponseGate gate,
            String command,
            String correlationId) {

        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-fallback");
            t.setDaemon(true);
            return t;
        });

        final long startedNs = System.nanoTime();
        try {
            Future<Boolean> future = worker.submit(() -> {
                try (LocalSocket s = outputServer.accept()) {
                    Logger.logDebug(LOG_TAG,
                        "Async handler connected via fallback socket (" + command + ")");
                    InputStream fromHandler = s.getInputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    long copied = 0;
                    while ((n = fromHandler.read(buf)) >= 0) {
                        gate.write(buf, 0, n);
                        gate.flush();
                        copied += n;
                    }
                    return copied > 0;
                }
            });

            Boolean copiedSomething = future.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (copiedSomething != null && copiedSomething) {
                gate.markDelivered();
                return true;
            }

            // Connected and closed without a byte. Commands that only perform an
            // action (Toast, Vibrate, Notification) legitimately answer with
            // nothing, so this stays a warning rather than an invented error.
            Logger.logWarn(LOG_TAG,
                    "Async handler for '" + command + "' connected but wrote no data");
            gate.markDelivered();
            return true;
        } catch (TimeoutException e) {
            long elapsedMs = (System.nanoTime() - startedNs) / 1000000L;
            Logger.logDebug(LOG_TAG, "Async handler for '" + command + "' did not respond within "
                    + ASYNC_TIMEOUT_SECONDS + " s");
            // One message for both shapes of failure: a handler that returned
            // without writing, and a Service that never connected. They are not
            // distinguishable from here, and both mean "no output for this command".
            gate.fail(correlationId, command,
                    withCommand(command, "handler produced no output - no result arrived within "
                            + ASYNC_TIMEOUT_SECONDS + " s (waited " + seconds(elapsedMs) + ")"),
                    "The handler returned without writing a result for this command. If a permission "
                    + "dialog is waiting, answer it and retry; otherwise this is a bug in Termux:API - "
                    + "run 'logcat -s SocketListener ResultReturner' and report the command name.",
                    null);
            return false;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            Logger.logStackTraceWithMessage(LOG_TAG,
                    "Error reading async handler result (" + command + ")", cause);
            gate.fail(correlationId, command,
                    withCommand(command, "the handler's result socket failed: "
                            + ResultReturner.describeThrowable(cause)),
                    "The handler ran but its result connection broke; run 'logcat -s SocketListener'.",
                    ResultReturner.exceptionClassName(cause));
            return false;
        } catch (InterruptedException e) {
            Logger.logDebug(LOG_TAG, "Async fallback interrupted (" + command + ")");
            Thread.currentThread().interrupt();
            gate.fail(correlationId, command,
                    withCommand(command, "interrupted while waiting for the handler"),
                    "The request was interrupted before the handler answered.", null);
            return false;
        } finally {
            worker.shutdownNow();
        }
    }

    // ─── Error-answer helpers ─────────────────────────────────────────

    /** @return {@code command + ": " + detail}, or just {@code detail} when unnamed. */
    private static String withCommand(String command, String detail) {
        return (command == null || command.isEmpty()) ? detail : command + ": " + detail;
    }

    /**
     * Reads one string member out of the raw request text.
     *
     * <p>Only used for diagnostics - the command name and the correlation id - so
     * a miss is not an error: it just means the message cannot name that part.</p>
     */
    private static String peekJsonString(Pattern pattern, String json) {
        if (json == null) return null;
        try {
            Matcher m = pattern.matcher(json);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) { }
        return null;
    }

    /** Elapsed milliseconds as seconds with one decimal, e.g. {@code 30.0 s}. */
    private static String seconds(long elapsedMs) {
        return String.format(Locale.ROOT, "%.1f s", elapsedMs / 1000.0);
    }

    /**
     * The client connection, wrapped so that exactly one answer can leave it.
     *
     * <p>The handler writes through this stream. When it never writes, or the
     * write fails, {@link #fail} sends a single JSON error object naming the
     * command; once an answer or an error has gone out nothing more is appended,
     * because a client that receives two objects cannot parse either of them.</p>
     */
    private static final class ResponseGate extends OutputStream {
        private final OutputStream target;
        private boolean delivered;
        private boolean errorSent;
        private long bytes;
        private boolean dropLogged;

        ResponseGate(OutputStream target) {
            this.target = target;
        }

        void markDelivered() {
            delivered = true;
        }

        boolean delivered() {
            return delivered;
        }

        @Override
        public void write(int b) throws IOException {
            if (dropAfterError(1)) return;
            target.write(b);
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (dropAfterError(len)) return;
            target.write(b, off, len);
            bytes += len;
        }

        /**
         * Refuses bytes that arrive after an error answer.
         *
         * <p>Reachable through one narrow race: the async handler starts writing
         * just as the wait times out, so the error has already gone out when its
         * bytes are copied. Dropping them keeps the client's "one document per
         * connection" guarantee, which matters more than the late bytes.</p>
         *
         * @return true when the bytes must be discarded.
         */
        private boolean dropAfterError(int len) {
            if (!errorSent) return false;
            if (!dropLogged) {
                dropLogged = true;
                Logger.logWarn(LOG_TAG, "Discarding " + len
                        + " byte(s) written after the error answer was sent");
            }
            return true;
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        /**
         * Sends the one error answer for this request.
         *
         * @return true if it was sent. False when the client already has an answer,
         *         when an error was sent before, or when the handler had already
         *         written bytes - a client cannot be handed two documents on one
         *         connection, so in that last case the failure goes to logcat only.
         */
        boolean fail(String correlationId, String command, String error, String hint,
                     String exceptionClass) {
            if (delivered || errorSent) return false;
            if (bytes > 0) {
                Logger.logWarn(LOG_TAG, "Not answering '" + command + "' again: " + bytes
                        + " bytes already reached the client, so the error stays in logcat: " + error);
                return false;
            }
            // Set before writing: a write that throws must not be retried later
            // and end up appending a second object to a half-written response.
            errorSent = true;
            try {
                JSONObject obj = new JSONObject();
                if (correlationId != null && !correlationId.isEmpty())
                    obj.put("correlationId", correlationId);
                obj.put("success", false);
                obj.put("error", error);
                if (command != null && !command.isEmpty()) obj.put("command", command);
                if (exceptionClass != null && !exceptionClass.isEmpty())
                    obj.put("exception", exceptionClass);
                if (hint != null && !hint.isEmpty()) obj.put("hint", hint);

                byte[] payload = (obj.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                target.write(payload);
                target.flush();
                bytes += payload.length;
                Logger.logError(LOG_TAG, "Answered command '" + command + "' with an error: " + error);
                return true;
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                        "Could not send the error for command '" + command + "'", t);
                return false;
            }
        }

        void flushQuietly() {
            try {
                target.flush();
            } catch (IOException ignored) { }
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

            // The ack byte is the legacy client's only verdict on the command: 0x00
            // meant "accepted" and was written even when the handler threw, so a
            // crashed command looked like a successful one that returned nothing.
            Throwable failure = null;
            try {
                DirectDispatcher.dispatch(directReceiver, app.getApplicationContext(), intent);
            } catch (Throwable t) {
                failure = t;
                Logger.logStackTraceWithMessage(LOG_TAG,
                        "Handler threw for legacy request (api_method="
                        + intent.getStringExtra("api_method") + ")", t);
            } finally {
                try {
                    String method = intent.getStringExtra("api_method");
                    if (failure == null) {
                        out.write(0);
                    } else {
                        out.write(1);
                        out.write(" " + withCommand(method, "handler threw "
                                + ResultReturner.describeThrowable(failure)) + "\n");
                    }
                    out.flush();
                } catch (IOException ignored) { }
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG,
                    "Connection error (legacy request could not be read)", e);
        }
    }
}
