#ifndef TERMUX_API_H
#define TERMUX_API_H

#include <stdlib.h>
#include <stdint.h>
#include <stddef.h>

#if defined(__cplusplus)
extern "C" {
#endif

/* Fallback: exec `am broadcast` when direct socket is unavailable */
_Noreturn void exec_am_broadcast(int, char**, char*, char*);

/* Try direct socket to plugin; fallback to exec_am_broadcast() */
_Noreturn void contact_plugin(int, char**, char*, char*);

/* Exec termux-callback with optional FD forwarding (termux-usb) */
_Noreturn void exec_callback(int);

/* Generate UUID v4-like string for abstract socket addresses */
void generate_uuid(char*);

/* Thread: accept output socket → forward stdin to Java */
void* transmit_stdin_to_socket(void*);

/* Read response from input socket → stdout; return received FD or -1 */
int transmit_socket_to_stdout(int);

/* Main entry point: connect to SocketListener, send command, return FD */
int run_api_command(int, char**);

/* Process exit status for the command line client.  run_api_command() keeps
 * returning the received descriptor (or -1) so fd-passing APIs like termux-usb
 * still work, while the status that main() reports is tracked here:
 *   0 = ok, 1 = usage error, 2 = could not reach the API, 3 = no response. */
extern int termux_api_exit_status;

#define TERMUX_API_EXIT_OK 0
#define TERMUX_API_EXIT_USAGE 1
#define TERMUX_API_EXIT_NO_CONNECTION 2
#define TERMUX_API_EXIT_NO_RESPONSE 3

/* ─── JNI-accessible utility functions ─────────────────────────── */

/** JSON-escape a string (adds surrounding quotes). Caller must free(). */
char* json_escape(const char* s);

/** Base64-encode raw bytes. Caller must free() the result. */
char* b64_encode(const uint8_t* data, size_t len, size_t* out_len);

/** Build a JSON command string from argc/argv. Caller must free(). */
char* build_json_command(int argc, char** argv, size_t* out_len);

#if defined(__cplusplus)
}
#endif

#endif /* TERMUX_API_H */
