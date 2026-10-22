#define _POSIX_SOURCE
#define _GNU_SOURCE
#define _DEFAULT_SOURCE

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "termux-api.h"

#define TERMUX_API_PACKAGE_VERSION "0.59.1"

#ifndef PREFIX
# define PREFIX "/data/data/com.termux/files/apps/com.termux"
#endif

#define LISTEN_SOCKET_ADDRESS "com.termux.api://listen"
#define FILESYSTEM_SOCKET_PATH "/data/data/com.termux/files/termux-api.sock"
#define SOCKET_TIMEOUT_SEC 30
#define STDIN_MAX_BYTES (64 * 1024 * 1024)  /* 64 MB — safety cap for inline stdin */

/* Exit status reported by main(); see termux-api.h. */
int termux_api_exit_status = TERMUX_API_EXIT_OK;

/* Seconds to wait for the handler's response; --timeout overrides it. */
static int response_timeout_sec = SOCKET_TIMEOUT_SEC;

/* Whether --timeout was given explicitly, in which case it is also honoured as
 * the wait for the streaming back-connect instead of the short default. */
static int response_timeout_explicit = 0;

/* How long to wait for the app to open the streaming back-connect socket
 * (see transmit_socket_to_stdout()). A handler that streams connects before it
 * starts waiting for data, so this is a connect deadline, not a data deadline. */
#define STREAM_CONNECT_GRACE_SEC 5

/* Set by run_api_command() so streaming failures can name the command and the
 * address the client was waiting on instead of printing a bare timeout. */
static const char *stream_diag_method = NULL;
static const char *stream_diag_addr = NULL;

static void print_usage(FILE *out, const char *prog) {
    fprintf(out,
        "Usage: %s <method> [extras...]\n"
        "\n"
        "Send one Termux API call to the running app and print its response.\n"
        "\n"
        "Arguments\n"
        "  --es  <key> <string>    string extra\n"
        "  -e    <key> <string>    same as --es\n"
        "  --ez  <key> <bool>      boolean extra (true/false)\n"
        "  --ei  <key> <int>       integer extra\n"
        "  --ef  <key> <float>     float extra\n"
        "  --esa <key> <a,b,c>     string array extra (comma separated)\n"
        "  --eia <key> <1,2,3>     int array extra\n"
        "  --ela <key> <1,2,3>     long array extra\n"
        "  -a    <action>          intent action to dispatch\n"
        "  --timeout <seconds>     how long to wait for a response (default %d)\n"
        "  -h, --help              this help\n"
        "  --version               print the client version\n"
        "\n"
        "Data piped on stdin is inlined for methods that accept it\n"
        "(Clipboard, SmsSend, Tts, StorageGet, MediaScanner).\n"
        "\n"
        "Exit status: 0 ok, 1 usage error, 2 API unreachable, 3 no response.\n"
        "\n"
        "Examples\n"
        "  %s BatteryStatus\n"
        "  %s Toast --es text \"hello\" --ez short false\n"
        "  %s Notification --es title Build --es content done --ei id 7\n"
        "  echo hello | %s Clipboard Set --es text hello\n",
        prog, SOCKET_TIMEOUT_SEC, prog, prog, prog, prog);
}

/*
 * JSON protocol client for Termux API.
 *
 * Architecture (SINGLE CONNECTION — no ephemeral sockets, no back-connect):
 *   1. Connect to DirectDispatcher socket
 *   2. Build JSON command from argv (method + typed extras)
 *   3. Send: [2-byte length-prefix][JSON bytes]
 *   4. Read response on the SAME connection until EOF
 *   5. Write response to stdout, return
 *
 * BENEFITS vs. legacy protocol:
 *   - No ephemeral socket pair (input/output) — saves 2 bind+listen+accept per cmd
 *   - No back-connect from ResultReturner — response comes on same connection
 *   - No 7-regex parsing on Java side — single-pass JSON.parse()
 *   - JSON natively types int/float/bool — no --ez/--ei/--ef guessing
 *   - Inline stdin (base64 in JSON) — no separate socket for input
 *
 * For streaming APIs (Sensor, Location) the connection stays open and the
 * Java side streams newline-delimited JSON objects until the client
 * disconnects.
 */

/* ─── Helpers ─────────────────────────────────────────────────── */

static bool send_all(int fd, const void *data, size_t len) {
    const char *p = (const char *)data;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n > 0) {
            p += n;
            len -= (size_t)n;
        } else if (n == -1 && errno == EINTR) {
            continue;
        } else {
            return false;
        }
    }
    return true;
}

static int connect_dispatcher(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd == -1) { perror("socket"); return -1; }

    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    socklen_t addrlen;

    /*
     * Try abstract namespace socket first, then fall back to
     * filesystem socket (which works inside proot where SELinux
     * may block abstract sockets).  Each gets a short retry loop
     * in case SocketListener is still initialising.
     *
     * Order:  abstract → filesystem → legacy am broadcast
     */

    /* ── 1. Abstract socket ────────────────────────────────────── */
    memcpy(addr.sun_path + 1, LISTEN_SOCKET_ADDRESS, strlen(LISTEN_SOCKET_ADDRESS));
    addrlen = (socklen_t)(sizeof(sa_family_t) + strlen(LISTEN_SOCKET_ADDRESS) + 1);

    for (int attempt = 0;; attempt++) {
        if (connect(fd, (struct sockaddr *)&addr, addrlen) == 0)
            return fd;

        if (errno == ECONNREFUSED || errno == ENOENT) {
            if (attempt < 3) { usleep(200000); continue; }
            break; /* give up on abstract, try filesystem */
        }
        /* Permanent failure — break to try filesystem */
        break;
    }

    /* ── 2. Filesystem socket ──────────────────────────────────── */
    size_t fs_path_len = strlen(FILESYSTEM_SOCKET_PATH);
    if (fs_path_len > 0 && fs_path_len < sizeof(addr.sun_path)) {
        memset(addr.sun_path, 0, sizeof(addr.sun_path));
        memcpy(addr.sun_path, FILESYSTEM_SOCKET_PATH, fs_path_len);
        addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + fs_path_len);

        for (int attempt = 0;; attempt++) {
            if (connect(fd, (struct sockaddr *)&addr, addrlen) == 0)
                return fd;

            if (errno == ECONNREFUSED || errno == ENOENT) {
                if (attempt < 3) { usleep(200000); continue; }
                break;
            }
            break;
        }
    }

    /* ── Both failed ────────────────────────────────────────────── */
    fprintf(stderr, "ERROR: Cannot connect to Termux API (abstract+filesystem).\n"
                    "       Falling back to legacy am broadcast.\n");
    close(fd);
    return -1;
}

/* ─── Base64 ─────────────────────────────────────────────────── */

static const char b64_alphabet[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static void b64_encode_block(const uint8_t in[3], char out[4], int n) {
    out[0] = b64_alphabet[in[0] >> 2];
    out[1] = b64_alphabet[((in[0] & 0x03) << 4) | (n > 1 ? in[1] >> 4 : 0)];
    out[2] = (n > 1) ? b64_alphabet[((in[1] & 0x0F) << 2) | (n > 2 ? in[2] >> 6 : 0)] : '=';
    out[3] = (n > 2) ? b64_alphabet[in[2] & 0x3F] : '=';
}

char *b64_encode(const uint8_t *data, size_t len, size_t *out_len) {
    size_t cap = ((len + 2) / 3) * 4 + 1;
    char *out = malloc(cap);
    if (!out) return NULL;
    size_t pos = 0;
    for (size_t i = 0; i < len; i += 3) {
        uint8_t block[3] = {0, 0, 0};
        int n = 3;
        if (i + 3 > len) { n = (int)(len - i); }
        for (int j = 0; j < n; j++) block[j] = data[i + j];
        b64_encode_block(block, out + pos, n);
        pos += 4;
    }
    out[pos] = '\0';
    if (out_len) *out_len = pos;
    return out;
}

/* ─── JSON value quoting ─────────────────────────────────────── */

char *json_escape(const char *s) {
    if (!s) return strdup("null");
    size_t cap = strlen(s) * 2 + 3;
    char *out = malloc(cap);
    if (!out) return NULL;
    size_t pos = 0;
    out[pos++] = '"';
    for (; *s; s++) {
        switch (*s) {
            case '"':  out[pos++] = '\\'; out[pos++] = '"';  break;
            case '\\': out[pos++] = '\\'; out[pos++] = '\\'; break;
            case '\n': out[pos++] = '\\'; out[pos++] = 'n';  break;
            case '\r': out[pos++] = '\\'; out[pos++] = 'r';  break;
            case '\t': out[pos++] = '\\'; out[pos++] = 't';  break;
            default:
                out[pos++] = *s;
                break;
        }
        if (pos + 3 >= cap) { cap *= 2; out = realloc(out, cap); }
    }
    out[pos++] = '"';
    out[pos] = '\0';
    return out;
}

/* ─── Build JSON command from argv ─────────────────────────────
 *
 * Parses args like:
 *   termux-api BatteryStatus --ez on true --ei num 42 --es key "val"
 *   termux-api Sensor --es -s "accelerometer" --el -d 1000000
 *   echo "hello" | termux-api Clipboard --es action set
 *
 * Recognised flags: --es, -e (string), --ez (boolean), --ei (int),
 *   --ef (float), --esa (string array), --eia (int array),
 *   --ela (long array), -a (action).
 *
 * If stdin is not a terminal AND the API is not a known
 * no-input type, stdin is read and included as "stdin"
 * (base64-encoded) and "stdinMime" in args.
 */

static int is_static_method(const char *method) {
    /* Methods that NEVER read stdin → skip stdin capture */
    if (!method) return 0;
    const char *noinput[] = {
        "BatteryStatus", "Brightness", "CameraInfo",
        "Dialog", "Fingerprint", "Notification", "NotificationChannel",
        "NotificationList", "NotificationRemove", "NotificationReply",
        "Sensor", "Share", "Toast", "Torch", "Vibrate", "Volume",
        "Wallpaper", "WifiConnectionInfo", "WifiScanInfo",
        "WifiEnable", "Wifi", "AudioInfo", "CallLog",
        "InfraredFrequencies", "InfraredTransmit", "MediaPlayer",
        "Nfc", "Keystore", "Nsd", "Companion",
        NULL
    };
    for (int i = 0; noinput[i]; i++)
        if (strcmp(method, noinput[i]) == 0) return 1;
    return 0;
}

static int is_stdin_api(const char *method) {
    /* Methods that may read stdin */
    if (!method) return 0;
    const char *yesinput[] = {
        "Clipboard",    // set action may read value from stdin
        "StorageGet",   // may read data
        "SmsSend",      // message body
        "MediaScanner", // file list
        "Tts",          // TextToSpeech
        NULL
    };
    for (int i = 0; yesinput[i]; i++)
        if (strcmp(method, yesinput[i]) == 0) return 1;
    return 0;
}

char *build_json_command(int argc, char **argv, size_t *out_len) {
    /* argv[0] = binary name, argv[1] = method, argv[2+] = extras */
    if (argc < 2) { *out_len = 0; return NULL; }
    const char *method = argv[1];

    /* First pass: calculate buffer size */
    size_t cap = 256; /* {"method":"...","args":{}} overhead */
    int has_stdin = 0;
    int arg_count = 0;

    for (int i = 2; i < argc; i++) {
        const char *flag = argv[i];

        if (strcmp(flag, "--es") == 0 || strcmp(flag, "-e") == 0 ||
            strcmp(flag, "--ez") == 0 || strcmp(flag, "--ei") == 0 ||
            strcmp(flag, "--ef") == 0 || strcmp(flag, "--esa") == 0 ||
            strcmp(flag, "--eia") == 0 || strcmp(flag, "--ela") == 0) 
        {
            arg_count++;
            if (i + 2 < argc) i += 2;
            else if (i + 1 < argc) i += 1;
        } else if (strcmp(flag, "-a") == 0) {
            if (i + 1 < argc) {
                i += 1;
                cap += 64; /* "intentAction":"value" overhead */
            }
        } else {
            arg_count++;
        }
    }

    /* Check stdin */
    if (!isatty(STDIN_FILENO) && !is_static_method(method) && is_stdin_api(method)) {
        has_stdin = 1;
        cap += 128; /* "stdin" + "stdinMime" overhead */
    }

    /* arg overhead: key(escaped) + : + value approx 2x key + 2x val + 8 */
    cap += arg_count * 80;

    cap += 64; /* safety */

    char *buf = malloc(cap);
    if (!buf) { *out_len = 0; return NULL; }

    /* Build JSON */
    size_t pos = snprintf(buf, cap, "{\"method\":\"%s\"", method);

    /* Args */
    int first_arg = 1;
    /* The action is a top-level field of the request, so it is collected
     * here instead of being written where the flag happened to appear. */
    char *intent_action = NULL;
    for (int i = 2; i < argc; i++) {
        const char *flag = argv[i];

        if (strcmp(flag, "--es") == 0 || strcmp(flag, "-e") == 0) {
            const char *key = (i + 1 < argc) ? argv[++i] : NULL;
            const char *val = (i + 1 < argc) ? argv[++i] : NULL;
            if (!key) continue;
            char *ek = json_escape(key);
            char *ev = json_escape(val ? val : "");
            if (first_arg) { pos += snprintf(buf + pos, cap - pos, ",\"args\":{"); first_arg = 0; }
            else { pos += snprintf(buf + pos, cap - pos, ","); }
            pos += snprintf(buf + pos, cap - pos, "%s:%s", ek, ev);
            free(ek); free(ev);
        }
        else if (strcmp(flag, "--esa") == 0) {
            const char *key = (i + 1 < argc) ? argv[++i] : NULL;
            const char *val = (i + 1 < argc) ? argv[++i] : NULL;
            if (!key) continue;
            char *ek = json_escape(key);
            if (first_arg) { pos += snprintf(buf + pos, cap - pos, ",\"args\":{"); first_arg = 0; }
            else { pos += snprintf(buf + pos, cap - pos, ","); }
            pos += snprintf(buf + pos, cap - pos, "%s:[", ek);
            free(ek);
            /* tokenise comma-separated values */
            char *copy = strdup(val ? val : "");
            int first_item = 1;
            for (char *tok = strtok(copy, ","); tok; tok = strtok(NULL, ",")) {
                if (!first_item) buf[pos++] = ',';
                first_item = 0;
                char *et = json_escape(tok);
                pos += snprintf(buf + pos, cap - pos, "%s", et);
                free(et);
            }
            free(copy);
            buf[pos++] = ']';
        }
        else if (strcmp(flag, "--ez") == 0) {
            const char *key = (i + 1 < argc) ? argv[++i] : NULL;
            const char *val = (i + 1 < argc) ? argv[++i] : "true";
            if (!key) continue;
            char *ek = json_escape(key);
            bool bval = (strcmp(val, "true") == 0 || strcmp(val, "t") == 0 || strcmp(val, "1") == 0);
            if (first_arg) { pos += snprintf(buf + pos, cap - pos, ",\"args\":{"); first_arg = 0; }
            else { pos += snprintf(buf + pos, cap - pos, ","); }
            pos += snprintf(buf + pos, cap - pos, "%s:%s", ek, bval ? "true" : "false");
            free(ek);
        }
        else if (strcmp(flag, "--ei") == 0) {
            const char *key = (i + 1 < argc) ? argv[++i] : NULL;
            const char *val = (i + 1 < argc) ? argv[++i] : "0";
            if (!key) continue;
            char *ek = json_escape(key);
            long ival = atol(val);
            if (first_arg) { pos += snprintf(buf + pos, cap - pos, ",\"args\":{"); first_arg = 0; }
            else { pos += snprintf(buf + pos, cap - pos, ","); }
            pos += snprintf(buf + pos, cap - pos, "%s:%ld", ek, ival);
            free(ek);
        }
        else if (strcmp(flag, "--eia") == 0 || strcmp(flag, "--ela") == 0) {
            const char *key = (i + 1 < argc) ? argv[++i] : NULL;
            const char *val = (i + 1 < argc) ? argv[++i] : NULL;
            if (!key) continue;
            char *ek = json_escape(key);
            if (first_arg) { pos += snprintf(buf + pos, cap - pos, ",\"args\":{"); first_arg = 0; }
            else { pos += snprintf(buf + pos, cap - pos, ","); }
            pos += snprintf(buf + pos, cap - pos, "%s:[", ek);
            free(ek);
            char *copy = strdup(val ? val : "");
            int first_item = 1;
            for (char *tok = strtok(copy, ","); tok; tok = strtok(NULL, ",")) {
                if (!first_item) buf[pos++] = ',';
                first_item = 0;
                pos += snprintf(buf + pos, cap - pos, "%s", tok);
            }
            free(copy);
            buf[pos++] = ']';
        }
        else if (strcmp(flag, "--ef") == 0) {
            const char *key = (i + 1 < argc) ? argv[++i] : NULL;
            const char *val = (i + 1 < argc) ? argv[++i] : "0.0";
            if (!key) continue;
            char *ek = json_escape(key);
            double fval = atof(val);
            if (first_arg) { pos += snprintf(buf + pos, cap - pos, ",\"args\":{"); first_arg = 0; }
            else { pos += snprintf(buf + pos, cap - pos, ","); }
            pos += snprintf(buf + pos, cap - pos, "%s:%g", ek, fval);
            free(ek);
        }
        else if (strcmp(flag, "-a") == 0) {
            if (i + 1 < argc) {
                const char *val = argv[++i];
                /* Kept aside: it is written as a top-level field once the
                 * args object is closed. Writing it here put it inside
                 * "args" whenever the flag followed an extra, and the app
                 * then dispatched the request without an action and
                 * answered "Unknown command: null". */
                free(intent_action);
                intent_action = strdup(val ? val : "");
            }
        }
        else {
            /* Bare value (legacy compatibility) — treat as string key with a
             * true value, e.g. `Clipboard Set` becomes {"Set": true}.  The key
             * must keep both quotes: dropping the opening one produced
             * {"args":{Set:true}}, which is not valid JSON at all. */
            char *ek = json_escape(flag);
            if (first_arg) { pos += snprintf(buf + pos, cap - pos, ",\"args\":{"); first_arg = 0; }
            else { pos += snprintf(buf + pos, cap - pos, ","); }
            pos += snprintf(buf + pos, cap - pos, "%s:true", ek);
            free(ek);
        }

        if (pos + 256 >= cap) { cap *= 2; buf = realloc(buf, cap); }
    }

    if (!first_arg) {
        buf[pos++] = '}'; /* close args */
    }

    /* The action belongs to the request, not to its arguments. It is
     * written here, after "args" is closed, so the order of the flags on
     * the command line cannot change the shape of the request. */
    if (intent_action) {
        if (pos + 256 >= cap) {
            char *grown = realloc(buf, cap * 2);
            if (!grown) { free(buf); free(intent_action); *out_len = 0; return NULL; }
            buf = grown;
            cap *= 2;
        }
        char *ev = json_escape(intent_action);
        pos += snprintf(buf + pos, cap - pos, ",\"intentAction\":%s", ev);
        free(ev);
        free(intent_action);
    }

    /* Inline stdin */
    if (has_stdin) {
        struct stat st;
        if (fstat(STDIN_FILENO, &st) == 0 && S_ISREG(st.st_mode) && st.st_size > 0) {
            size_t file_size = (size_t)(st.st_size < STDIN_MAX_BYTES ? st.st_size : STDIN_MAX_BYTES);
            uint8_t *stdin_data = malloc(file_size + 1);
            if (stdin_data) {
                size_t total = 0;
                while (total < file_size) {
                    ssize_t n = read(STDIN_FILENO, stdin_data + total, file_size - total);
                    if (n <= 0) break;
                    total += (size_t)n;
                }
                stdin_data[total] = '\0';

                size_t b64_len = 0;
                char *b64 = b64_encode(stdin_data, total, &b64_len);
                if (b64) {
                    /* Add stdin data as separate args fields */
                    /* "stdin" as base64 string */
                    pos += snprintf(buf + pos, cap - pos, ",\"stdin\":\"%s\"", b64);
                    /* "stdinLen" as int */
                    pos += snprintf(buf + pos, cap - pos, ",\"stdinLen\":%zu", total);
                    free(b64);
                }
                free(stdin_data);
            }
        } else {
            /* Pipe — read until EOF */
            size_t total = 0, buf_cap = 65536;
            uint8_t *stdin_data = malloc(buf_cap);
            if (stdin_data) {
                while (total < STDIN_MAX_BYTES) {
                    if (total + 4096 > buf_cap) { buf_cap *= 2; stdin_data = realloc(stdin_data, buf_cap); }
                    ssize_t n = read(STDIN_FILENO, stdin_data + total, buf_cap - total);
                    if (n <= 0) break;
                    total += (size_t)n;
                }

                size_t b64_len = 0;
                char *b64 = b64_encode(stdin_data, total, &b64_len);
                if (b64) {
                    pos += snprintf(buf + pos, cap - pos, ",\"stdin\":\"%s\"", b64);
                    pos += snprintf(buf + pos, cap - pos, ",\"stdinLen\":%zu", total);
                    free(b64);
                }
                free(stdin_data);
            }
        }
    }

    /* Close JSON root */
    buf[pos++] = '}';
    buf[pos] = '\0';
    *out_len = pos;
    return buf;
}

/* ─── Read response on the dispatcher connection ────────────────
 *
 * Reads all data from fd until EOF, writes to stdout.
 * If SCM_RIGHTS file descriptors arrive (termux-usb), returns
 * the first one.  Otherwise returns -1.
 */
static int read_response_on_connection(int fd) {
    char buf[4096];
    char cbuf[256];
    struct iovec io = { .iov_base = buf, .iov_len = sizeof(buf) };
    struct msghdr msg = { .msg_iov = &io, .msg_iovlen = 1,
                          .msg_control = cbuf, .msg_controllen = sizeof(cbuf) };
    int received_fd = -1;

    ssize_t n;
    for (;;) {
        n = recvmsg(fd, &msg, 0);
        if (n > 0) {
        } else if (n == 0) {
            break;
        } else if (errno == EINTR) {
            msg.msg_controllen = sizeof(cbuf);
            continue;
        } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
            /* SO_RCVTIMEO fired — the handler never wrote a response. */
            fprintf(stderr, "ERROR: no response within %d s\n", response_timeout_sec);
            termux_api_exit_status = TERMUX_API_EXIT_NO_RESPONSE;
            n = 0;
            break;
        } else {
            break;
        }
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        if (cmsg && cmsg->cmsg_len == CMSG_LEN(sizeof(int)) && cmsg->cmsg_type == SCM_RIGHTS) {
            received_fd = *((int *)CMSG_DATA(cmsg));
        }
        if (received_fd != -1 && n == 1 && buf[0] == '@') n = 0;
        if (n > 0) write(STDOUT_FILENO, buf, (size_t)n);
        msg.msg_controllen = sizeof(cbuf);
    }
    if (n < 0) perror("recvmsg");
    return received_fd;
}

/* Forward declarations for legacy/streaming helpers used by run_api_command */
static int create_abstract_server_socket(const char*, struct sockaddr_un*);
static void set_accept_timeout(int, int);
static int run_api_command_fallback(int, char**);
void generate_uuid(char*);
int transmit_socket_to_stdout(int);

/* ─── Streaming API detection ──────────────────────────────────
 *
 * These methods start Android Services that write data back over
 * a long-lived socket connection.  For these we still create an
 * output socket so the Service can write data asynchronously.
 */
static int is_streaming_method(const char *method) {
    if (!method) return 0;
    const char *streaming[] = {
        "Sensor", "Location", "MicRecorder", "SpeechToText",
        "TextToSpeech", "NotificationList",
        NULL
    };
    for (int i = 0; streaming[i]; i++)
        if (strcmp(method, streaming[i]) == 0) return 1;
    return 0;
}

/* ─── run_api_command (NEW — JSON protocol) ──────────────────────
 *
 *   SIMPLE APIs (no streaming, no FD):
 *     1. Connect to dispatcher
 *     2. Build JSON command
 *     3. Send length-prefixed JSON on dispatcher connection
 *     4. Read response on SAME connection → stdout
 *     5. Close connection
 *
 *   STREAMING APIs (Sensor, Location, MicRecorder, SpeechToText,
 *                   TextToSpeech, NotificationList):
 *     1. Create 1 ephemeral OUTPUT socket (NOT 2 — stdin is inline)
 *     2. Build JSON with socket_output="<address>" INSIDE "args"
 *     3. Connect to dispatcher, send JSON
 *     4. Read response on the OUTPUT socket (the app back-connects to it)
 *
 *   socket_output must be an "args" member: ApiRequest.fromJson() turns only
 *   that object into intent extras and SocketListener reads the address from
 *   the intent. A root-level socket_output is silently discarded, the request
 *   is handled as non-streaming, and the answer goes to the dispatcher
 *   connection the client never reads - which is what produced the old
 *   30 s "streaming connection never arrived" hang.
 *
 *   Compared to OLD protocol:
 *     - NO input socket (ephemeral) → stdin goes inline in JSON
 *     - NO legacy string format    → JSON command
 *     - NO 7-regex parsing on Java → single APIRequest.parse()
 *     - Streaming still uses a back-connect (ResultReturner connects to
 *       socket_output); only the non-streaming answer is written directly on
 *       the dispatcher connection
 */
int run_api_command(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        printf("%s\n", TERMUX_API_PACKAGE_VERSION);
        fflush(stdout);
        return -1;
    }
    if (argc >= 2 && (strcmp(argv[1], "-h") == 0 || strcmp(argv[1], "--help") == 0)) {
        print_usage(stdout, "termux-api");
        return -1;
    }
    if (argc < 2) {
        print_usage(stderr, "termux-api");
        termux_api_exit_status = TERMUX_API_EXIT_USAGE;
        return -1;
    }

    /* --timeout N is a client-side option, so drop it from argv before the
     * JSON builder runs — otherwise it would be sent to the app as a bare
     * `"--timeout": true` argument (unknown flags become boolean extras). */
    for (int i = 2; i < argc; i++) {
        if (strcmp(argv[i], "--timeout") != 0) continue;
        if (i + 1 >= argc) {
            fprintf(stderr, "ERROR: --timeout needs a value in seconds\n");
            termux_api_exit_status = TERMUX_API_EXIT_USAGE;
            return -1;
        }
        int t = atoi(argv[i + 1]);
        if (t > 0) {
            response_timeout_sec = t;
            response_timeout_explicit = 1;
        }
        for (int j = i; j + 2 < argc; j++) argv[j] = argv[j + 2];
        argc -= 2;
        i--;
    }

    const char *method = argv[1];
    int streaming = is_streaming_method(method);

    struct sigaction sa = { .sa_handler = SIG_IGN, .sa_flags = SA_RESTART };
    sigaction(SIGPIPE, &sa, NULL);

    /* ── Try connecting to Dispatcher socket FIRST ───────────────
     *
     * If the socket is unavailable (Termux app not running), fall
     * back to the legacy am broadcast protocol immediately — before
     * building JSON or reading stdin.
     */
    int fd = connect_dispatcher();
    if (fd == -1) {
        /* The legacy path shells out to $PREFIX/bin/am.  When that binary is
         * missing (app not installed, or PREFIX not deployed) there is nothing
         * to wait for, so fail immediately instead of burning the response
         * timeout — scripts used to hang 30 s on every call in that state. */
        if (access(PREFIX "/bin/am", X_OK) != 0) {
            fprintf(stderr,
                "ERROR: Termux API unreachable and " PREFIX "/bin/am is missing.\n"
                "       Is the Termux app installed and running?\n");
            termux_api_exit_status = TERMUX_API_EXIT_NO_CONNECTION;
            return -1;
        }
        int rc = run_api_command_fallback(argc, argv);
        if (rc == -1) termux_api_exit_status = TERMUX_API_EXIT_NO_CONNECTION;
        return rc;
    }

    /* --timeout applies to both writing the request and reading the answer, so
     * a handler that never replies cannot hang a script forever. */
    struct timeval tv = { .tv_sec = response_timeout_sec, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

    /* ── Create output socket for streaming APIs ──────────────── */
    int output_server_fd = -1;
    char output_addr_str[100] = "";
    struct sockaddr_un output_addr;

    if (streaming) {
        generate_uuid(output_addr_str);
        output_server_fd = create_abstract_server_socket(output_addr_str, &output_addr);
        if (output_server_fd == -1) { close(fd); return 1; }
        set_accept_timeout(output_server_fd, response_timeout_sec);
        /* Diagnostics only: a failure message has to say which command and
         * which address nobody connected to. */
        stream_diag_method = method;
        stream_diag_addr = output_addr_str;
    }

    /* ── Build JSON command ──────────────────────────────────────
     *
     * socket_output has to travel INSIDE "args". ApiRequest.fromJson() only
     * mirrors the "args" object into intent extras and drops every other
     * root-level key, so appending it to the JSON root (the old splice below)
     * made SocketListener's getStringExtra("socket_output") return null: the
     * request was then handled as a non-streaming one, the answer was written
     * to the request connection that a streaming client never reads, and the
     * client sat on its own output socket until its 30 s timeout printed
     * "no response within 30 s (streaming connection never arrived)".
     *
     * Adding it as a normal "--es socket_output <addr>" argument lets the
     * builder place it in "args" (and open "args" at all when the command has
     * no other extras, e.g. `termux-api MicRecorder -a record`).
     */
    char **json_argv = argv;
    int json_argc = argc;
    if (streaming && output_addr_str[0]) {
        json_argv = malloc(sizeof(char *) * (size_t)(argc + 3));
        if (!json_argv) {
            close(fd);
            if (output_server_fd != -1) close(output_server_fd);
            fprintf(stderr, "ERROR: Out of memory\n");
            return 1;
        }
        memcpy(json_argv, argv, sizeof(char *) * (size_t)argc);
        json_argv[argc]     = "--es";
        json_argv[argc + 1] = "socket_output";
        json_argv[argc + 2] = output_addr_str;
        json_argc = argc + 3;
    }

    size_t cmd_len;
    char *json = build_json_command(json_argc, json_argv, &cmd_len);
    if (json_argv != argv) free(json_argv);
    if (!json) {
        close(fd);
        if (output_server_fd != -1) close(output_server_fd);
        fprintf(stderr, "ERROR: Out of memory\n");
        return 1;
    }

    /* ── Send JSON command ─────────────────────────────────────── */
    uint16_t net_len = htons((uint16_t)cmd_len);
    bool ok = send_all(fd, &net_len, 2) && send_all(fd, json, cmd_len);
    free(json);

    if (!ok) {
        fprintf(stderr, "ERROR: Failed to send command\n");
        termux_api_exit_status = TERMUX_API_EXIT_NO_CONNECTION;
        close(fd);
        if (output_server_fd != -1) close(output_server_fd);
        return 1;
    }

    /* ── Read response ─────────────────────────────────────────── */
    int received_fd = -1;

    if (streaming) {
        received_fd = transmit_socket_to_stdout(output_server_fd);
        close(output_server_fd);
    } else {
        received_fd = read_response_on_connection(fd);
    }

    close(fd);
    return received_fd >= 0 ? received_fd : -1;
}

/* ─── run_api_command_fallback (legacy am broadcast protocol) ───
 *
 * Used when the JSON socket is unavailable.  Creates ephemeral
 * I/O sockets, forks, exec's `am broadcast` with the legacy
 * string-extras protocol, and reads the response from the
 * back-connect socket.
 *
 * Socket layout (legacy convention):
 *   input_server_fd  (at input_addr)  ← Java writes response, C reads
 *   output_server_fd (at output_addr) → C writes stdin, Java reads
 *
 * In exec_am_broadcast the address mapping is swapped so that
 * Java's `socket_input` (read) points to C's output_addr and
 * `socket_output` (write) points to C's input_addr.
 */
static int run_api_command_fallback(int argc, char **argv) {
    char input_addr[100] = "", output_addr[100] = "";
    struct sockaddr_un input_sock, output_sock;
    int input_server_fd = -1, output_server_fd = -1;

    generate_uuid(input_addr);
    generate_uuid(output_addr);

    /*
     * input_server_fd — Java connects here to WRITE the response.
     * output_server_fd — Java connects here to READ stdin data.
     */
    input_server_fd = create_abstract_server_socket(input_addr, &input_sock);
    output_server_fd = create_abstract_server_socket(output_addr, &output_sock);

    if (input_server_fd == -1 || output_server_fd == -1) {
        if (input_server_fd != -1) close(input_server_fd);
        if (output_server_fd != -1) close(output_server_fd);
        fprintf(stderr, "ERROR: Cannot create fallback sockets. "
                        "Is the Termux app installed?\n");
        return -1;
    }

    /* ── Fork: child exec's am broadcast ───────────────────────── */
    pid_t pid = fork();
    if (pid == -1) {
        close(input_server_fd);
        close(output_server_fd);
        fprintf(stderr, "ERROR: fork() failed for am broadcast\n");
        return -1;
    }

    if (pid == 0) {
        /* CHILD — never returns */
        close(input_server_fd);
        close(output_server_fd);
        exec_am_broadcast(argc, argv, input_addr, output_addr);
        _exit(1);
    }

    /* ── Parent: handle stdin (if any) + read response ────────── */

    pid_t stdin_pid = -1;

    if (!isatty(STDIN_FILENO)) {
        /* Stdin has pipe data — fork a child to send it to Java.
         * The child takes ownership of output_server_fd. */
        stdin_pid = fork();
        if (stdin_pid == -1) {
            close(output_server_fd);
        } else if (stdin_pid == 0) {
            /* STDIN CHILD: accept on output_server_fd, read stdin, write to socket */
            struct sockaddr_un remote;
            socklen_t addrlen = sizeof(remote);
            int client = accept(output_server_fd, (struct sockaddr *)&remote, &addrlen);
            if (client == -1) { close(output_server_fd); _exit(0); }

            char buf[4096];
            ssize_t n;
            while ((n = read(STDIN_FILENO, buf, sizeof(buf))) > 0) {
                if (write(client, buf, (size_t)n) < 0) break;
            }
            shutdown(client, SHUT_WR);
            close(client);
            close(output_server_fd);
            _exit(0);
        } else {
            close(output_server_fd);
        }
    } else {
        close(output_server_fd);
    }

    /* ── Read response from Java (input_server_fd) ──────────────
     *
     * poll() with timeout before accept() so we don't block
     * forever if Java never connects.  Retry on EINTR from
     * SIGCHLD (fork child exit).
     */
    struct sockaddr_un remote;
    socklen_t addrlen;
    int response_fd;
    for (;;) {
        struct pollfd pfd = { .fd = input_server_fd, .events = POLLIN };
        int pr = poll(&pfd, 1, response_timeout_sec * 1000);
        if (pr < 0) {
            if (errno == EINTR) continue;
            perror("poll(input)");
            close(input_server_fd);
            goto fallback_cleanup;
        }
        if (pr == 0) {
            fprintf(stderr, "ERROR: Timeout waiting for Java response.\n");
            close(input_server_fd);
            goto fallback_cleanup;
        }
        addrlen = sizeof(remote);
        response_fd = accept(input_server_fd, (struct sockaddr *)&remote, &addrlen);
        if (response_fd >= 0) break;
        if (errno != EINTR) {
            perror("accept(input)");
            close(input_server_fd);
            goto fallback_cleanup;
        }
    }
    close(input_server_fd);

    /* Read response, check for SCM_RIGHTS FD */
    char cbuf[256];
    struct iovec io = { .iov_base = NULL, .iov_len = 0 };
    struct msghdr msg = { .msg_iov = &io, .msg_iovlen = 0,
                          .msg_control = cbuf, .msg_controllen = sizeof(cbuf) };
    int received_fd = -1;
    if (recvmsg(response_fd, &msg, MSG_PEEK) > 0) {
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        if (cmsg && cmsg->cmsg_len == CMSG_LEN(sizeof(int)) && cmsg->cmsg_type == SCM_RIGHTS)
            received_fd = *((int *)CMSG_DATA(cmsg));
    }

    char io_buf[4096];
    ssize_t n;
    while ((n = read(response_fd, io_buf, sizeof(io_buf))) > 0 || (n == -1 && errno == EINTR)) {
        if (n == -1) continue;
        if (received_fd != -1 && n == 1 && io_buf[0] == '@') n = 0;
        if (n > 0) write(STDOUT_FILENO, io_buf, (size_t)n);
    }
    close(response_fd);

fallback_cleanup:
    /* Close output socket if stdin child hasn't taken it */
    if (stdin_pid == -1 && output_server_fd != -1) close(output_server_fd);

    /* Wait for children */
    int status;
    waitpid(pid, &status, 0);
    if (stdin_pid > 0) waitpid(stdin_pid, NULL, 0);

    return received_fd >= 0 ? received_fd : -1;
}

/* ─── Legacy fallback helpers ───────────────────────────────────
 *
 * These keep the old ephemeral-socket protocol for the
 * am-broadcast fallback path.
 */

/* Keep generate_uuid + helpers for legacy fallback */
#ifdef __ANDROID__
# include <android/api-level.h>
#endif

static uint32_t get_random(void) {
#ifdef __ANDROID__
    if (android_get_device_api_level() < 28) {
        static int urandom_fd = -1;
        if (urandom_fd == -1) {
            urandom_fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
            if (urandom_fd == -1)
                return (uint32_t)(getpid() ^ time(NULL));
        }
        uint32_t val;
        if (read(urandom_fd, &val, sizeof(val)) == sizeof(val))
            return val;
        return (uint32_t)(getpid() ^ time(NULL));
    }
#endif
    return arc4random();
}

static uint32_t get_random_range(uint32_t min, uint32_t max) {
    uint32_t range = max - min + 1;
    uint32_t threshold = -range % range;
    uint32_t val;
    do { val = get_random(); } while (val < threshold);
    return min + (val % range);
}

void generate_uuid(char *str) {
    sprintf(str, "%x%x-%x-%x-%x-%x%x%x",
            get_random(), get_random(),
            (uint32_t)getpid(),
            ((get_random() & 0x0fff) | 0x4000),
            get_random_range(0x8000, 0xbfff),
            get_random(), get_random(), get_random());
}

/* Ephemeral socket server for legacy back-connect */
static int create_abstract_server_socket(const char *addr_str, struct sockaddr_un *addr) {
    addr->sun_family = AF_UNIX;
    memset(addr->sun_path, 0, sizeof(addr->sun_path));
    strncpy(&addr->sun_path[1], addr_str, strlen(addr_str));

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd == -1) { perror("socket"); return -1; }
    socklen_t addrlen = (socklen_t)(sizeof(sa_family_t) + strlen(addr_str) + 1);
    if (bind(fd, (struct sockaddr *)addr, addrlen) == -1) { perror("bind"); close(fd); return -1; }
    if (listen(fd, 1) == -1) { perror("listen"); close(fd); return -1; }
    return fd;
}

static void set_accept_timeout(int fd, int sec) {
    struct timeval tv = { .tv_sec = sec, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
}

void* transmit_stdin_to_socket(void* arg) {
    int server_fd = *((int *)arg);
    struct sockaddr_un remote;
    socklen_t addrlen = sizeof(remote);
    int client_fd = accept(server_fd, (struct sockaddr *)&remote, &addrlen);
    if (client_fd == -1) return NULL;

    char buf[4096];
    ssize_t n;
    while ((n = read(STDIN_FILENO, buf, sizeof(buf))) > 0) {
        if (write(client_fd, buf, (size_t)n) < 0) break;
    }
    shutdown(client_fd, SHUT_WR);
    close(client_fd);
    return NULL;
}

int transmit_socket_to_stdout(int input_server_fd) {
    /*
     * Wait for the app to connect to our output socket.  This is not the data
     * timeout: a handler that supports streaming connects *before* it starts
     * waiting for sensor fixes, so a connection that has not arrived after a
     * few seconds is not going to arrive at all.  Waiting the full response
     * timeout here is what turned a protocol mismatch into a 30 s hang that
     * printed nothing actionable.
     */
    const int grace_sec = response_timeout_explicit
                          ? response_timeout_sec
                          : (response_timeout_sec < STREAM_CONNECT_GRACE_SEC
                             ? response_timeout_sec : STREAM_CONNECT_GRACE_SEC);

    struct pollfd pfd = { .fd = input_server_fd, .events = POLLIN };
    int pr = poll(&pfd, 1, grace_sec * 1000);
    if (pr <= 0) {
        if (pr == 0) {
            fprintf(stderr,
                "ERROR: no response from the Termux API app - it never opened the streaming connection.\n"
                "       method       : %s\n"
                "       socket_output: \"%s\"\n"
                "       Waited %d s after the request was accepted. No data can arrive any\n"
                "       more, because no handler is writing to this address. Usual cause is a\n"
                "       Termux:API app build older than this client (it ignores the address),\n"
                "       or a handler that aborted before replying (permission denied, service\n"
                "       crashed). Update/Reinstall Termux:API and retry; see\n"
                "       `logcat -s TermuxApiReceiver SocketListener` for the handler side.\n"
                "       If the handler is only slow to start, retry with --timeout <seconds>.\n",
                stream_diag_method ? stream_diag_method : "?",
                stream_diag_addr ? stream_diag_addr : "?",
                grace_sec);
            termux_api_exit_status = TERMUX_API_EXIT_NO_RESPONSE;
        }
        else perror("poll(input)");
        return -1;
    }
    struct sockaddr_un remote;
    socklen_t addrlen = sizeof(remote);
    int client_fd = accept(input_server_fd, (struct sockaddr *)&remote, &addrlen);
    if (client_fd == -1) { perror("accept(input)"); return -1; }

    /* A handler that connects and then stalls must not hang the command
     * forever either: bound the read as well, at twice the response timeout so
     * that a slow `-r updates` stream is not cut short by an idle gap. */
    struct timeval read_tv = { .tv_sec = response_timeout_sec * 2, .tv_usec = 0 };
    setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &read_tv, sizeof(read_tv));

    char cbuf[256];
    struct iovec io = { .iov_base = NULL, .iov_len = 0 };
    struct msghdr msg = { .msg_iov = &io, .msg_iovlen = 0,
                          .msg_control = cbuf, .msg_controllen = sizeof(cbuf) };
    int fd = -1;
    if (recvmsg(client_fd, &msg, MSG_PEEK) > 0) {
        struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
        if (cmsg && cmsg->cmsg_len == CMSG_LEN(sizeof(int)) && cmsg->cmsg_type == SCM_RIGHTS)
            fd = *((int *)CMSG_DATA(cmsg));
    }

    char buf[4096];
    ssize_t n;
    long long streamed = 0;
    while ((n = read(client_fd, buf, sizeof(buf))) > 0 || (n == -1 && errno == EINTR)) {
        if (n == -1) continue;
        if (fd != -1 && n == 1 && buf[0] == '@') n = 0;
        if (n > 0) {
            write(STDOUT_FILENO, buf, (size_t)n);
            streamed += n;
        }
    }
    if (streamed == 0 && n == 0) {
        /* Connected, then closed without a single byte. Every handler that
         * supports streaming writes at least one JSON object, a result line or
         * an API_ERROR, so this is a handler bug - and it used to look exactly
         * like success to a script (empty stdout, exit 0). */
        fprintf(stderr,
            "ERROR: the handler connected to socket_output \"%s\" (method %s) and wrote nothing.\n"
            "       An empty stream is a handler failure, not an empty result. Repeat with\n"
            "       `logcat -s ResultReturner SocketListener` to see what the handler did.\n",
            stream_diag_addr ? stream_diag_addr : "?",
            stream_diag_method ? stream_diag_method : "?");
        termux_api_exit_status = TERMUX_API_EXIT_NO_RESPONSE;
    } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        /* Stream went silent: the handler connected but stopped writing and
         * never closed. Report it instead of exiting 0 with partial output. */
        fprintf(stderr,
            "ERROR: streaming connection went silent for %d s (method %s, socket_output \"%s\").\n"
            "       The handler stopped writing without closing the stream.\n",
            response_timeout_sec * 2,
            stream_diag_method ? stream_diag_method : "?",
            stream_diag_addr ? stream_diag_addr : "?");
        termux_api_exit_status = TERMUX_API_EXIT_NO_RESPONSE;
    } else if (n < 0) {
        perror("read");
    }
    close(client_fd);
    return fd;
}

/* ─── exec_am_broadcast (fallback) ──────────────────────────────
 * Uses OLD legacy extras protocol (am broadcast --es ...).
 * The TermuxApiReceiver gets the extras as Intent extras
 * and the old ResultReturner back-connect path handles I/O.
 */
_Noreturn void exec_am_broadcast(int argc, char** argv,
                                  char* input_address_string,
                                  char* output_address_string)
{
    close(STDOUT_FILENO);
    open("/dev/null", O_RDONLY);

    struct sigaction sa = { .sa_handler = SIG_IGN, .sa_flags = 0 };
    sigaction(SIGPIPE, &sa, NULL);

    const int pre = 14;
    const int post = argc - 1;
    const int total = pre + post;

    char **child_argv = malloc(sizeof(char*) * (size_t)(total + 1));
    if (!child_argv) { perror("malloc"); exit(1); }

    child_argv[0]  = "am";
    child_argv[1]  = "broadcast";
    child_argv[2]  = "--user";
    child_argv[3]  = "0";
    child_argv[4]  = "-n";
    child_argv[5]  = "com.termux.api/.TermuxApiReceiver";
    child_argv[6]  = "--es";
    child_argv[7]  = "socket_input";
    child_argv[8]  = output_address_string;
    child_argv[9]  = "--es";
    child_argv[10] = "socket_output";
    child_argv[11] = input_address_string;
    child_argv[12] = "--es";
    child_argv[13] = "api_method";

    memcpy(child_argv + pre, argv + 1, (size_t)post * sizeof(char*));
    child_argv[total] = NULL;

    execv(PREFIX "/bin/am", child_argv);
    free(child_argv);
    perror("execv(am)");
    exit(1);
}

/* ─── contact_plugin ─────────────────────────────────────────────
 * Tries JSON protocol on direct socket; if it fails falls back
 * to exec_am_broadcast with legacy protocol.
 */
_Noreturn void contact_plugin(int argc, char** argv,
                               char* input_address_string,
                               char* output_address_string)
{
    struct sigaction sa = { .sa_handler = SIG_IGN, .sa_flags = SA_RESTART };
    sigaction(SIGPIPE, &sa, NULL);

    int fd = connect_dispatcher();
    if (fd != -1) {
        size_t cmd_len;
        char *json = build_json_command(argc, argv, &cmd_len);
        if (json) {
            uint16_t net_len = htons((uint16_t)cmd_len);
            if (send_all(fd, &net_len, 2) && send_all(fd, json, cmd_len)) {
                /* Read ACK: first byte == 0 → success */
                char ack;
                if (read(fd, &ack, 1) == 1 && ack == 0) {
                    close(fd);
                    free(json);
                    exit(0);
                }
            }
            free(json);
        }
        close(fd);
    }

    /* Fallback to am broadcast with legacy protocol */
    exec_am_broadcast(argc, argv, input_address_string, output_address_string);
}

_Noreturn void exec_callback(int fd) {
    char *fds = NULL;
    if (asprintf(&fds, "%d", fd) == -1) { perror("asprintf"); exit(1); }

    char *export_env = getenv("TERMUX_EXPORT_FD");
    if (export_env && strcmp(export_env, "true") == 0) {
        if (setenv("TERMUX_USB_FD", fds, 1) == -1) perror("setenv");
        execl(PREFIX "/libexec/termux-callback", "termux-callback", NULL);
        perror("execl(" PREFIX "/libexec/termux-callback)");
    } else {
        execl(PREFIX "/libexec/termux-callback", "termux-callback", fds, NULL);
        perror("execl(" PREFIX "/libexec/termux-callback)");
    }
    exit(1);
}
