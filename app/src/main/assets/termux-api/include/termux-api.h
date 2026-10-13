#ifndef TERMUX_API_H
#define TERMUX_API_H

#include <stdlib.h>

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

#if defined(__cplusplus)
}
#endif

#endif /* TERMUX_API_H */
