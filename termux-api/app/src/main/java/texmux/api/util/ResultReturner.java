package texmux.api.util;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.ParcelFileDescriptor;
import android.util.JsonWriter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import texmux.shared.android.PackageUtils;
import texmux.shared.file.FileUtils;
import texmux.shared.logger.Logger;
import texmux.shared.termux.TermuxConstants;
import texmux.shared.termux.plugins.TermuxPluginUtils;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Utility for returning API call results back to the caller.
 *
 * <p>Three modes:</p>
 * <ol>
 *   <li><b>Broadcast path:</b> writes result via {@code goAsync()} / {@code PendingResult}.</li>
 *   <li><b>Legacy socket path:</b> creates back-connect sockets to the client's
 *       {@code socket_input}/{@code socket_output} addresses.</li>
 *   <li><b>Direct path (JSON / daemon):</b> writes to a
 *       {@link ThreadLocal} output stream set by the caller — no socket handshake.</li>
 * </ol>
 */
public abstract class ResultReturner {

    @SuppressLint("StaticFieldLeak")
    public static Context context;

    private static final String LOG_TAG = "ResultReturner";

    /**
     * An extra intent parameter which specifies a unix socket address where output from the API
     * call should be written.
     */
    private static final String SOCKET_OUTPUT_EXTRA = "socket_output";

    /**
     * An extra intent parameter which specifies a unix socket address where input to the API call
     * can be read from.
     */
    private static final String SOCKET_INPUT_EXTRA = "socket_input";

    // ─── Direct response mode (bidirectional / daemon path) ──────────
    //
    // When these ThreadLocals are set, returnData() writes the result
    // directly to the given stream instead of creating back-connect
    // sockets.  The caller (SocketListener JSON path or ApiDaemon) sets
    // them before dispatch and clears them after.

    private static final ThreadLocal<OutputStream> directResponseStream = new ThreadLocal<>();
    private static final ThreadLocal<InputStream> directInputStream = new ThreadLocal<>();
    private static final ThreadLocal<Runnable> directResponseHook = new ThreadLocal<>();

    /**
     * Set the input stream that the next {@link #returnData} call in this
     * thread should read from, instead of creating a back-connect socket.
     * Used by {@link texmux.api.SocketListener} when the JSON command
     * includes inline base64-encoded stdin data.
     */
    public static void setDirectInputStream(InputStream in) {
        directInputStream.set(in);
    }

    /** Clear the direct input stream for this thread. */
    public static void clearDirectInputStream() {
        directInputStream.remove();
    }

    /**
     * Set the output stream that the next {@link #returnData} call in this
     * thread should write to, instead of creating a back-connect socket.
     *
     * @return the previous direct response stream (or {@code null}), so
     *         callers can save/restore it for nested use
     */
    public static OutputStream setDirectResponseStream(OutputStream out) {
        OutputStream prev = directResponseStream.get();
        directResponseStream.set(out);
        return prev;
    }

    /** Clear the direct response stream for this thread. */
    public static void clearDirectResponseStream() {
        directResponseStream.remove();
    }

    /**
     * Set a hook that runs when the response has been written (or when
     * {@link #returnData} decides no response is needed).  Used by
     * {@link texmux.api.SocketListener} to know when to send the
     * JSON response.
     */
    public static void setDirectResponseHook(Runnable hook) {
        directResponseHook.set(hook);
    }

    /**
     * Clear the response hook for this thread.
     *
     * <p>Must be called when the request ends: the hook captures the client's
     * output stream, so leaving it set would keep a closed socket (and its
     * buffers) reachable from a pooled thread for as long as the process lives.</p>
     */
    public static void clearDirectResponseHook() {
        directResponseHook.remove();
    }

    // ─── Failure reporting (what the client is told when nothing was written) ──
    //
    // Before this existed the only trace of a failing handler was a logcat
    // entry: the client was handed one anonymous "Handler did not produce a
    // response" object or, on the async path, zero bytes. Everything below
    // exists so that a failure names its command and throwable in the answer
    // the client already reads.

    /**
     * Set by {@link texmux.api.SocketListener} on requests that arrived over
     * the JSON socket.  When set, a handler that throws must answer with a JSON
     * error object instead of closing the socket without a byte, because the JSON
     * client treats "no bytes" exactly like a hang.
     */
    public static final String EXTRA_JSON_ERRORS = "__termux_api_json_errors";

    /** Correlation id echoed back in that error object. */
    public static final String EXTRA_CORRELATION_ID = "__termux_api_correlation_id";

    /**
     * A response write that failed, described for the client.
     *
     * <p>Holds text rather than the {@link Throwable}: by the time it is read the
     * request is over, and a retained throwable would pin its whole object graph
     * to a pooled thread, which is how a single failure turns into a leak.</p>
     */
    public static final class Failure {
        public final String exceptionClass;
        public final String detail;

        Failure(String exceptionClass, String detail) {
            this.exceptionClass = exceptionClass;
            this.detail = detail;
        }

        /** e.g. {@code java.io.IOException: broken pipe} - one line, never a stack trace. */
        public String describe() {
            return detail;
        }
    }

    private static final ThreadLocal<Failure> directWriteFailure = new ThreadLocal<>();

    /** The failed response write for this thread, or {@code null} when it succeeded. */
    public static Failure getDirectWriteFailure() {
        return directWriteFailure.get();
    }

    /** Clear the recorded response write failure for this thread. */
    public static void clearDirectWriteFailure() {
        directWriteFailure.remove();
    }

    private static void noteDirectWriteFailure(Throwable t) {
        directWriteFailure.set(new Failure(exceptionClassName(t), describeThrowable(t)));
    }

    /** Fully qualified exception class name, e.g. {@code java.lang.NullPointerException}. */
    public static String exceptionClassName(Throwable t) {
        return t == null ? "java.lang.Throwable" : t.getClass().getName();
    }

    /**
     * One-line description of a throwable for the client: class name plus message,
     * clipped and stripped of newlines.
     *
     * <p>Deliberately not a stack trace: this text is shown to the user and pasted
     * into bug reports, and a trace there buries the cause instead of naming it.
     * The full trace still goes to logcat.</p>
     */
    public static String describeThrowable(Throwable t) {
        if (t == null) return "java.lang.Throwable";
        String className = exceptionClassName(t);
        String message = t.getMessage();
        if (message == null) return className;
        String flattened = oneLine(message);
        if (flattened.isEmpty()) return className;
        return className + ": " + flattened;
    }

    /** @return {@code s} with control characters replaced by spaces, clipped to 300 chars. */
    static String oneLine(String s) {
        StringBuilder sb = new StringBuilder(Math.min(s.length(), 300));
        for (int i = 0; i < s.length() && sb.length() < 300; i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') sb.append(' ');
            else if (c >= 0x20) sb.append(c);
        }
        if (s.length() > 300) sb.append(" ...");
        return sb.toString().trim();
    }

    // ─── ResultWriter types (unchanged) ──────────────────────────────

    public interface ResultWriter {
        void writeResult(PrintWriter out) throws Exception;
    }

    public static abstract class WithInput implements ResultWriter {
        protected InputStream in;

        public void setInput(InputStream inputStream) throws Exception {
            this.in = inputStream;
        }
    }

    public static abstract class BinaryOutput implements ResultWriter {
        private OutputStream out;

        public void setOutput(OutputStream outputStream) {
            this.out = outputStream;
        }

        public abstract void writeResult(OutputStream out) throws Exception;

        public final void writeResult(PrintWriter unused) throws Exception {
            writeResult(out);
            out.flush();
        }
    }

    public static abstract class WithStringInput extends WithInput {
        protected String inputString;

        protected boolean trimInput() {
            return true;
        }

        @Override
        public final void setInput(InputStream inputStream) throws Exception {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int l;
            while ((l = inputStream.read(buffer)) > 0) {
                baos.write(buffer, 0, l);
            }
            inputString = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            if (trimInput()) inputString = inputString.trim();
        }
    }

    public static abstract class WithAncillaryFd implements ResultWriter {
        private LocalSocket outputSocket = null;
        private final ParcelFileDescriptor[] pfds = { null };

        public final void setOutputSocketForFds(LocalSocket outputSocket) {
            this.outputSocket = outputSocket;
        }

        public final void sendFd(PrintWriter out, int fd) {
            if (this.pfds[0] != null) {
                Logger.logStackTraceWithMessage(LOG_TAG, "File descriptor already sent", new Exception());
                return;
            }

            this.pfds[0] = ParcelFileDescriptor.adoptFd(fd);
            FileDescriptor[] fds = { pfds[0].getFileDescriptor() };

            outputSocket.setFileDescriptorsForSend(fds);

            out.print("@");
            out.flush();

            outputSocket.setFileDescriptorsForSend(null);
        }

        public final void cleanupFds() {
            if (this.pfds[0] != null) {
                try {
                    this.pfds[0].close();
                } catch (IOException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close file descriptor", e);
                }
            }
        }
    }

    public static abstract class ResultJsonWriter implements ResultWriter {
        @Override
        public final void writeResult(PrintWriter out) throws Exception {
            JsonWriter writer = new JsonWriter(out);
            writer.setIndent("  ");
            writeJson(writer);
            out.println();
        }

        public abstract void writeJson(JsonWriter out) throws Exception;
    }

    // ─── Public API ──────────────────────────────────────────────────

    public static void noteDone(BroadcastReceiver receiver, final Intent intent) {
        returnData(receiver, intent, null);
    }

    public static void copyIntentExtras(Intent origIntent, Intent newIntent) {
        newIntent.putExtra("api_method", origIntent.getStringExtra("api_method"));
        newIntent.putExtra(SOCKET_OUTPUT_EXTRA, origIntent.getStringExtra(SOCKET_OUTPUT_EXTRA));
        newIntent.putExtra(SOCKET_INPUT_EXTRA, origIntent.getStringExtra(SOCKET_INPUT_EXTRA));
    }

    @SuppressLint("SdCardPath")
    public static LocalSocketAddress getApiLocalSocketAddress(@NonNull Context context,
                                                               @NonNull String socketLabel, @NonNull String socketAddress) {
        if (socketAddress.startsWith("/")) {
            ApplicationInfo termuxApplicationInfo = PackageUtils.getApplicationInfoForPackage(context,
                    TermuxConstants.TERMUX_PACKAGE_NAME);
            if (termuxApplicationInfo == null) {
                throw new RuntimeException("Failed to get ApplicationInfo for the Termux app package: " +
                        TermuxConstants.TERMUX_PACKAGE_NAME);
            }

            List<String> termuxAppDataDirectories = Arrays.asList(termuxApplicationInfo.dataDir,
                    "/data/data/" + TermuxConstants.TERMUX_PACKAGE_NAME);
            if (!FileUtils.isPathInDirPaths(socketAddress, termuxAppDataDirectories, true)) {
                throw new RuntimeException("The " + socketLabel + " socket address \"" + socketAddress + "\"" +
                        " is not under Termux app data directories: " + termuxAppDataDirectories);
            }

            return new LocalSocketAddress(socketAddress, Namespace.FILESYSTEM);
        } else {
            return new LocalSocketAddress(socketAddress, Namespace.ABSTRACT);
        }
    }

    public static boolean shouldRunThreadForResultRunnable(Object context) {
        if (context instanceof IntentService) return false;
        // Direct path: run synchronously, no thread creation
        if (directResponseStream.get() != null) return false;
        if (context == ResultReturner.context) return false;
        return true;
    }

    /**
     * Return the result of an API call to the caller.
     *
     * <p>If a {@link #setDirectResponseStream direct response stream} has been
     * set for the current thread, the result is written there synchronously.
     * Otherwise the legacy back-connect path is used.</p>
     */
    public static void returnData(Object context, final Intent intent, final ResultWriter resultWriter) {
        final BroadcastReceiver receiver = (BroadcastReceiver) ((context instanceof BroadcastReceiver) ? context : null);
        final Activity activity = (Activity) ((context instanceof Activity) ? context : null);
        final PendingResult asyncResult = (receiver != null && receiver.isOrderedBroadcast()) ? receiver.goAsync() : null;

        final Throwable callerStackTrace = shouldRunThreadForResultRunnable(context) ? new Exception("Called by:") : null;

        final Runnable runnable = () -> {
            // ── Check for direct (in-process) response path ──────────
            OutputStream directOut = directResponseStream.get();
            if (directOut != null) {
                writeDirect(directOut, intent, resultWriter);
                return;
            }

            // ── Legacy back-connect path ─────────────────────────────
            PrintWriter writer = null;
            LocalSocket outputSocket = null;
            CountingOutputStream countedOut = null;
            try {
                outputSocket = new LocalSocket();
                String outputSocketAddress = intent.getStringExtra(SOCKET_OUTPUT_EXTRA);
                if (outputSocketAddress == null || outputSocketAddress.isEmpty())
                    throw new IOException("Missing '" + SOCKET_OUTPUT_EXTRA + "' extra");
                Logger.logDebug(LOG_TAG, "Connecting to output socket \"" + outputSocketAddress + "\"");
                outputSocket.connect(getApiLocalSocketAddress(ResultReturner.context, "output", outputSocketAddress));
                // Count what actually leaves for the client: a handler that throws
                // after writing half an object must not be reported as if it had
                // written nothing, and a failed write must not pass for success.
                countedOut = new CountingOutputStream(outputSocket.getOutputStream());
                writer = new PrintWriter(countedOut);

                if (resultWriter != null) {
                    if (resultWriter instanceof WithAncillaryFd) {
                        ((WithAncillaryFd) resultWriter).setOutputSocketForFds(outputSocket);
                    }
                    if (resultWriter instanceof BinaryOutput) {
                        ((BinaryOutput) resultWriter).setOutput(countedOut);
                    }
                    if (resultWriter instanceof WithInput) {
                        try (LocalSocket inputSocket = new LocalSocket()) {
                            String inputSocketAddress = intent.getStringExtra(SOCKET_INPUT_EXTRA);
                            if (inputSocketAddress == null || inputSocketAddress.isEmpty())
                                throw new IOException("Missing '" + SOCKET_INPUT_EXTRA + "' extra");
                            inputSocket.connect(getApiLocalSocketAddress(ResultReturner.context, "input", inputSocketAddress));
                            ((WithInput) resultWriter).setInput(inputSocket.getInputStream());
                            resultWriter.writeResult(writer);
                        }
                    } else {
                        resultWriter.writeResult(writer);
                    }
                    if (resultWriter instanceof WithAncillaryFd) {
                        ((WithAncillaryFd) resultWriter).cleanupFds();
                    }
                }

                if (asyncResult != null && receiver.isOrderedBroadcast()) {
                    asyncResult.setResultCode(0);
                } else if (activity != null) {
                    activity.setResult(0);
                }
            } catch (Throwable t) {
                String message = "Error in " + LOG_TAG;
                if (callerStackTrace != null)
                    t.addSuppressed(callerStackTrace);
                Logger.logStackTraceWithMessage(LOG_TAG, message, t);

                TermuxPluginUtils.sendPluginCommandErrorNotification(ResultReturner.context, LOG_TAG,
                        TermuxConstants.TERMUX_API_APP_NAME + " Error", message, t);

                // Answer instead of closing in silence. This throwable used to reach
                // logcat only, so an async handler (a Service writing after dispatch
                // returned) that failed before its first byte left the client reading
                // zero bytes - indistinguishable from a hang, and invisible to any
                // script that only looks at the exit status.
                //
                // Only when nothing was written: if bytes already went to the client
                // it has (part of) the answer, and appending a second object would
                // make both of them unparseable. Exactly one answer per request.
                if (writer != null && countedOut != null && countedOut.count() == 0
                        && intent.getBooleanExtra(EXTRA_JSON_ERRORS, false)
                        && !(resultWriter instanceof BinaryOutput)) {
                    writeJsonErrorLine(writer, intent, t);
                }

                if (asyncResult != null && receiver != null && receiver.isOrderedBroadcast()) {
                    asyncResult.setResultCode(1);
                } else if (activity != null) {
                    activity.setResult(1);
                }
            } finally {
                try {
                    if (writer != null) writer.close();
                    if (outputSocket != null) outputSocket.close();
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to close", e);
                }

                try {
                    if (asyncResult != null) {
                        asyncResult.finish();
                    } else if (activity != null) {
                        activity.finish();
                    }
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to finish", e);
                }
            }
        };

        if (shouldRunThreadForResultRunnable(context)) {
            new Thread(runnable).start();
        } else {
            runnable.run();
        }
    }

    public static void setContext(Context context) {
        ResultReturner.context = context.getApplicationContext();
        android.util.Log.d("ResultReturner", "setContext called");
    }

    // ─── Direct (in-process) writer ────────────────────────────────────

    /**
     * Write the result directly to an output stream, without back-connect
     * sockets.  Called when {@link #directResponseStream} is set.
     */
    private static void writeDirect(OutputStream out, Intent intent, ResultWriter resultWriter) {
        PrintWriter pw = null;
        boolean delivered = false;
        try {
            InputStream directIn = directInputStream.get();
            // Count what actually reaches the client: a writer that produces no
            // bytes must not be reported as an answer.
            CountingOutputStream counted = new CountingOutputStream(out);

            if (resultWriter != null) {
                if (resultWriter instanceof BinaryOutput) {
                    ((BinaryOutput) resultWriter).setOutput(counted);
                }
                if (resultWriter instanceof WithAncillaryFd) {
                    // FD transfer not supported in direct mode
                    Logger.logWarn(LOG_TAG, "FD transfer not supported in direct response mode");
                }

                pw = new PrintWriter(counted);
                if (resultWriter instanceof WithInput) {
                    // A handler that reads stdin gets an empty stream rather than a
                    // silent null, so it cannot NPE and report "no output" instead.
                    ((WithInput) resultWriter).setInput(directIn != null
                            ? directIn
                            : new ByteArrayInputStream(new byte[0]));
                }
                resultWriter.writeResult(pw);
            }

            // PrintWriter swallows IOException, so checkError() is the only way to
            // learn that the client is gone. Flushing here - and not in finally -
            // also keeps a half-written JSON object out of the socket when
            // writeResult() throws: better a clean error than truncated output.
            if (pw != null) {
                pw.flush();
                if (pw.checkError()) {
                    noteDirectWriteFailure(new IOException("write to the client socket failed (broken pipe?)"));
                } else if (counted.count() == 0) {
                    // A writer ran and produced nothing: the client would read zero
                    // bytes and SocketListener could not tell it from a hang.
                    noteDirectWriteFailure(new IOException(
                            "the handler's result writer wrote no output"));
                } else {
                    delivered = true;
                }
            } else {
                // Nothing to write (noteDone): the command did all it had to do.
                delivered = true;
            }
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in direct write", t);
            noteDirectWriteFailure(t);
        } finally {
            // Only claim "responded" when the client actually got the result. The
            // hook is what tells SocketListener to stay quiet, so firing it after a
            // failed write is what turned a real exception into silence.
            if (delivered) {
                Runnable hook = directResponseHook.get();
                if (hook != null) {
                    try { hook.run(); } catch (Exception ignored) {}
                }
            }
        }
    }

    // ─── Helpers for the back-connect path ─────────────────────────────

    /**
     * Answers a handler that threw before writing anything with one JSON error
     * object, so a client that reads the response can tell a crash from a hang.
     *
     * <p>Callers must only use this when no bytes have been written yet: a client
     * that has already received part of an answer cannot be given a second
     * document without breaking both.</p>
     */
    private static void writeJsonErrorLine(PrintWriter writer, Intent intent, Throwable t) {
        try {
            String command = intent.getStringExtra("api_method");
            JSONObject obj = new JSONObject();
            String correlationId = intent.getStringExtra(EXTRA_CORRELATION_ID);
            if (correlationId != null && !correlationId.isEmpty()) {
                obj.put("correlationId", correlationId);
            }
            obj.put("success", false);
            obj.put("error", (command == null || command.isEmpty() ? "" : command + ": ")
                    + "handler threw " + describeThrowable(t));
            if (command != null && !command.isEmpty()) {
                obj.put("command", command);
            }
            obj.put("exception", exceptionClassName(t));
            obj.put("hint", "The handler threw before it answered; the stack trace is in logcat under '"
                    + LOG_TAG + "'.");
            writer.print(obj.toString());
            writer.print('\n');
            writer.flush();
            if (writer.checkError()) {
                Logger.logWarn(LOG_TAG, "Error answer for '" + command
                        + "' could not be delivered to the client");
            }
        } catch (Throwable ignored) {
            // The throwable itself is already in logcat; there is nobody else to tell.
        }
    }

    /** Counts the bytes handed to the client, so a partial write is recognisable. */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream target;
        private long count;

        CountingOutputStream(OutputStream target) {
            this.target = target;
        }

        long count() {
            return count;
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }
    }
}
// force rebuild
