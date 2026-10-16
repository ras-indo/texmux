package com.termux.api.util;

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

import com.termux.shared.android.PackageUtils;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.plugins.TermuxPluginUtils;

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
     * Used by {@link com.termux.api.SocketListener} when the JSON command
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
     * {@link com.termux.api.SocketListener} to know when to send the
     * JSON response.
     */
    public static void setDirectResponseHook(Runnable hook) {
        directResponseHook.set(hook);
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
            try {
                outputSocket = new LocalSocket();
                String outputSocketAddress = intent.getStringExtra(SOCKET_OUTPUT_EXTRA);
                if (outputSocketAddress == null || outputSocketAddress.isEmpty())
                    throw new IOException("Missing '" + SOCKET_OUTPUT_EXTRA + "' extra");
                Logger.logDebug(LOG_TAG, "Connecting to output socket \"" + outputSocketAddress + "\"");
                outputSocket.connect(getApiLocalSocketAddress(ResultReturner.context, "output", outputSocketAddress));
                writer = new PrintWriter(outputSocket.getOutputStream());

                if (resultWriter != null) {
                    if (resultWriter instanceof WithAncillaryFd) {
                        ((WithAncillaryFd) resultWriter).setOutputSocketForFds(outputSocket);
                    }
                    if (resultWriter instanceof BinaryOutput) {
                        ((BinaryOutput) resultWriter).setOutput(outputSocket.getOutputStream());
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
    }

    // ─── Direct (in-process) writer ────────────────────────────────────

    /**
     * Write the result directly to an output stream, without back-connect
     * sockets.  Called when {@link #directResponseStream} is set.
     */
    private static void writeDirect(OutputStream out, Intent intent, ResultWriter resultWriter) {
        PrintWriter pw = null;
        try {
            InputStream directIn = directInputStream.get();

            if (resultWriter != null) {
                if (resultWriter instanceof BinaryOutput) {
                    ((BinaryOutput) resultWriter).setOutput(out);
                }
                if (resultWriter instanceof WithInput && directIn != null) {
                    ((WithInput) resultWriter).setInput(directIn);
                }
                if (resultWriter instanceof WithAncillaryFd) {
                    // FD transfer not supported in direct mode
                    Logger.logWarn(LOG_TAG, "FD transfer not supported in direct response mode");
                }

                pw = new PrintWriter(out);
                if (resultWriter instanceof WithInput && directIn != null) {
                    resultWriter.writeResult(pw);
                } else if (resultWriter instanceof WithInput) {
                    // WithInput but no input stream provided — write empty input
                    ((WithInput) resultWriter).setInput(new java.io.ByteArrayInputStream(new byte[0]));
                    resultWriter.writeResult(pw);
                } else {
                    resultWriter.writeResult(pw);
                }
            }
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Error in direct write", t);
        } finally {
            if (pw != null) {
                pw.flush();
            }
            // Fire response hook so SocketListener knows the response was sent
            Runnable hook = directResponseHook.get();
            if (hook != null) {
                try { hook.run(); } catch (Exception ignored) {}
            }
        }
    }
}
