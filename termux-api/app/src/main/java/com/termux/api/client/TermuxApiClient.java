package com.termux.api.client;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Command-line entry point that connects directly to the
 * {@link com.termux.api.SocketListener SocketListener} abstract-namespace
 * socket, bypassing {@code am broadcast} and the system ActivityManager
 * entirely.
 *
 * <p>Invoked from a shell script via {@code app_process}:
 * <pre>{@code
 * CLASSPATH=$(pm path com.termux | head -1 | cut -d: -f2) \
 *   app_process /system/bin com.termux.api.client.TermuxApiClient \
 *     BatteryStatus [--ez key true ...]
 * }</pre>
 *
 * <p>The protocol matches what the C binary ({@code termux-api-broadcast})
 * speaks:
 * <ol>
 *   <li>The client creates two abstract-namespace
 *       {@link LocalServerSocket}s (one for reading the result,
 *       one for forwarding stdin).</li>
 *   <li>It connects to the SocketListener's well-known listen address
 *       ({@code com.termux.api://listen}).</li>
 *   <li>It sends a {@code uint16} length (big-endian) followed by a
 *       command string in the same extra-flag format the old C binary
 *       produced.</li>
 *   <li>The SocketListener acknowledges with a single {@code 0x00} byte.</li>
 *   <li>After the handler calls
 *       {@link com.termux.api.util.ResultReturner#returnData
 *       ResultReturner.returnData()}, the Java process connects to the
 *       client's input socket and writes the JSON/text result.</li>
 *   <li>The client reads that data and writes it to stdout.</li>
 * </ol>
 */
public final class TermuxApiClient {

    /** Must match {@link com.termux.api.SocketListener#LISTEN_ADDRESS}. */
    private static final String LISTEN_ADDRESS = "com.termux.api://listen";

    private TermuxApiClient() {
        // Not instantiated
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TermuxApiClient <api_method> [extra_args...]");
            System.exit(1);
        }

        // --- 1. Create I/O sockets in the abstract namespace ---------------
        String inputAddr  = UUID.randomUUID().toString();
        String outputAddr = UUID.randomUUID().toString();

        LocalServerSocket inputServer  = new LocalServerSocket(inputAddr);
        LocalServerSocket outputServer = new LocalServerSocket(outputAddr);

        // --- 2. Connect to SocketListener ----------------------------------
        LocalSocket listenSocket = new LocalSocket();
        listenSocket.connect(
                new LocalSocketAddress(LISTEN_ADDRESS,
                                       LocalSocketAddress.Namespace.ABSTRACT));

        // --- 3. Build command string ---------------------------------------
        // Format (matching C binary output):
        //   --es socket_input "<outputAddr>" --es socket_output "<inputAddr>"
        //   --es api_method "<method>" [extra_args...]
        //
        // Note: socket_input/output are REVERSED from the client's perspective:
        //   socket_input  = the socket the CLIENT provides to WRITE data TO Java
        //   socket_output = the socket the CLIENT reads the RESULT FROM
        StringBuilder cmd = new StringBuilder();
        appendExtra(cmd, "--es", "socket_input", outputAddr);
        appendExtra(cmd, "--es", "socket_output", inputAddr);
        appendExtra(cmd, "--es", "api_method", args[0]);

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (i + 2 < args.length && ("--es".equals(arg) || "-e".equals(arg) || "--esa".equals(arg))) {
                // --es key "value" (three tokens)
                appendExtra(cmd, arg, args[i + 1], args[i + 2]);
                i += 2;
            } else if (i + 1 < args.length && ("--ez".equals(arg) || "--ei".equals(arg) || "--ef".equals(arg))) {
                // --ez key value (two tokens)
                cmd.append(arg).append(' ').append(args[i + 1]).append(' ').append(args[i + 2]).append(' ');
                i += 2;
            } else {
                cmd.append(arg).append(' ');
            }
        }

        byte[] cmdBytes = cmd.toString().getBytes(StandardCharsets.UTF_8);

        // --- 4. Send length-prefixed command --------------------------------
        DataOutputStream dos = new DataOutputStream(listenSocket.getOutputStream());
        dos.writeShort(cmdBytes.length);
        dos.write(cmdBytes);
        dos.flush();

        // --- 5. Read acknowledgment (0x00) ----------------------------------
        InputStream lis = listenSocket.getInputStream();
        int ack = lis.read();
        if (ack != 0) {
            // Error — read the error message
            StringBuilder errMsg = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while ((n = lis.read(buf)) >= 0) {
                errMsg.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            System.err.println("API error: " + errMsg);
            System.exit(1);
        }

        // Listen socket is no longer needed after acknowledgment
        listenSocket.close();

        // --- 6. Accept result connection ------------------------------------
        // ResultReturner.returnData() connects to socket_output (= our inputAddr)
        try (LocalSocket resultSocket = inputServer.accept();
             InputStream resultIn = resultSocket.getInputStream()) {

            // Forward stdin data to the output socket if an API needs it
            // (e.g. termux-storage-get).  Run in a daemon thread.
            Thread stdinThread = new Thread(() -> {
                try {
                    LocalSocket stdinSocket = outputServer.accept();
                    OutputStream stdinOut = stdinSocket.getOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = System.in.read(buf)) >= 0) {
                        stdinOut.write(buf, 0, n);
                    }
                    stdinOut.close();
                } catch (Exception ignored) {
                    // stdin forwarding is best-effort
                }
            });
            stdinThread.setDaemon(true);
            stdinThread.start();

            // Read all result data and write to stdout
            byte[] buf = new byte[4096];
            int n;
            while ((n = resultIn.read(buf)) >= 0) {
                System.out.write(buf, 0, n);
            }
            System.out.flush();
        }
    }

    private static void appendExtra(StringBuilder sb, String flag,
                                     String key, String value) {
        sb.append(flag).append(' ');
        // If the value contains special chars or is the last element, quote it
        boolean needsQuoting = value.indexOf(' ') >= 0
                             || value.indexOf('"') >= 0
                             || value.indexOf('\\') >= 0;
        if (needsQuoting) {
            sb.append(key).append(" \"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"') sb.append('\\');
                sb.append(c);
            }
            sb.append("\" ");
        } else {
            sb.append(key).append(' ').append(value).append(' ');
        }
    }
}
