/*
 * am-socket.c — Minimal AM socket client for Termux
 *
 * Connects to Termux app's Unix socket server and sends an
 * Android Activity Manager (am) command. No linker config
 * needed — syscalls only. Works inside proot containers.
 *
 * Protocol (AF_UNIX/SOCK_STREAM):
 *   client sends:  command_string
 *   server sends:  exit_code\0stdout\0stderr\0
 *
 * Usage: am-socket <command_string>
 *   e.g. am-socket "broadcast --user 0 -a com.termux.app.reload_style ..."
 *
 * Build:
 *   aarch64-linux-gnu-gcc -static -Os -s -o am-socket am-socket.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/un.h>

/* Termux AM socket path (@TERMUX_APPS_DIR@/@TERMUX_PACKAGE@/termux-am/am.sock) */
#define SOCKET_PATH "/data/data/com.termux/files/apps/com.termux/termux-am/am.sock"

static void
send_all(int fd, const char *data, int len)
{
	while (len > 0) {
		int r = (int)send(fd, data, (size_t)len, MSG_NOSIGNAL);
		if (r < 0) { perror("send"); exit(1); }
		len -= r;
		data += r;
	}
}

/* Read until \0, return 1 if \0 found */
static int
recv_until_null(int fd, char *buf, int size)
{
	int pos = 0;
	while (pos < size - 1) {
		int r = (int)recv(fd, buf + pos, 1, 0);
		if (r <= 0) return 0;
		if (buf[pos] == '\0') { buf[pos] = '\0'; return 1; }
		pos++;
	}
	buf[pos] = '\0';
	return 0;
}

int
main(int argc, char *argv[])
{
	const char *cmd;
	if (argc > 2) {
		fprintf(stderr, "am-socket only takes 1 argument but received %d\n", argc - 1);
		fprintf(stderr, "Usage: am-socket \"<full command string>\"\n");
		fprintf(stderr, "  (use termux-am or am instead for multi-arg commands)\n");
		return 1;
	}
	if (argc == 2) {
		cmd = argv[1];
	} else {
		/* Default: reload Termux settings */
		cmd = "broadcast --user 0 -a com.termux.app.reload_style "
		      "--ez recreate_activity false com.termux";
	}

	struct sockaddr_un addr;
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	if (strlen(SOCKET_PATH) >= sizeof(addr.sun_path)) {
		fprintf(stderr, "Socket path too long\n");
		return 1;
	}
	strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

	int fd = socket(AF_UNIX, SOCK_STREAM, 0);
	if (fd < 0) { perror("socket"); return 1; }

	if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
		perror("connect");
		close(fd);
		return 1;
	}

	/* Send command + shutdown write side */
	send_all(fd, cmd, (int)strlen(cmd));
	shutdown(fd, SHUT_WR);

	/* Read exit code */
	char buf[16];
	if (!recv_until_null(fd, buf, sizeof(buf))) {
		fprintf(stderr, "Failed to read exit code\n");
		close(fd);
		return 1;
	}
	int exit_code = atoi(buf);

	/* Read and print stdout */
	do {
		char obuf[4096];
		memset(obuf, 0, sizeof(obuf));
		if (!recv_until_null(fd, obuf, sizeof(obuf))) break;
		fputs(obuf, stdout);
	} while (1);

	/* Read and print stderr */
	do {
		char ebuf[4096];
		memset(ebuf, 0, sizeof(ebuf));
		if (!recv_until_null(fd, ebuf, sizeof(ebuf))) break;
		fputs(ebuf, stderr);
	} while (1);

	close(fd);
	return exit_code;
}
