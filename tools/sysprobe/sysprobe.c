/*
 * sysprobe — find which syscalls Android's app seccomp policy traps.
 *
 * Android installs a seccomp filter into every app process (from Zygote) and
 * the filter is inherited across fork/exec, so proot's tracees inherit it too.
 * A trapped syscall kills the caller with SIGSYS (signal 31 on arm64), which
 * looks like a silent death: proot reports exit 255 with no message.
 *
 * This tool calls each syscall number in a forked child (all-zero arguments)
 * and reports the ones whose child died with SIGSYS. Run it on the device as
 * the app user — it measures the very policy the guest would hit.
 *
 * Safe by construction: every call happens in a throwaway child, children get
 * an alarm(1) so a blocking syscall cannot hang the probe, and the syscalls
 * that could fork or terminate the probe itself are skipped.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <string.h>
#include <sys/wait.h>
#include <sys/syscall.h>

/* aarch64 syscall numbers that must never be called blindly from the probe */
static int skipped(long n)
{
    switch (n) {
    case 93:   /* exit        */
    case 94:   /* exit_group  */
    case 128:  /* restart_syscall */
    case 139:  /* rt_sigreturn */
    case 178:  /* rt_sigsuspend */
    case 220:  /* clone       */
    case 435:  /* clone3      */
        return 1;
    default:
        return 0;
    }
}

static const char *name(long n)
{
    switch (n) {
    case 220: return "clone";
    case 221: return "execve";
    case 222: return "mmap";
    case 226: return "mprotect";
    case 293: return "rseq";
    case 294: return "io_pgetevents";
    case 435: return "clone3";
    case 436: return "close_range";
    case 437: return "openat2";
    case 438: return "pidfd_getfd";
    case 439: return "faccessat2";
    case 440: return "process_madvise";
    case 441: return "epoll_pwait2";
    case 442: return "mount_setattr";
    case 443: return "quotactl_fd";
    case 444: return "landlock_create_ruleset";
    case 445: return "landlock_add_rule";
    case 446: return "landlock_restrict_self";
    case 447: return "memfd_secret";
    case 448: return "process_mrelease";
    case 449: return "futex_waitv";
    case 450: return "set_mempolicy_home_node";
    default:   return "";
    }
}

static void on_alarm(int sig) { (void)sig; _exit(0); }

int main(int argc, char **argv)
{
    long lo = 0, hi = 460, n;
    int trapped = 0;

    if (argc > 1) lo = atol(argv[1]);
    if (argc > 2) hi = atol(argv[2]);

    printf("sysprobe: scanning syscalls %ld..%ld\n", lo, hi);

    for (n = lo; n <= hi; n++) {
        pid_t pid;
        int st;

        if (skipped(n)) continue;

        pid = fork();
        if (pid < 0) { perror("fork"); return 2; }
        if (pid == 0) {
            struct sigaction sa;
            memset(&sa, 0, sizeof sa);
            sa.sa_handler = on_alarm;
            sigaction(SIGALRM, &sa, NULL);
            alarm(1);
            syscall(n, 0, 0, 0, 0, 0, 0);
            _exit(0);
        }

        if (waitpid(pid, &st, 0) < 0) continue;
        if (WIFSIGNALED(st) && WTERMSIG(st) == SIGSYS) {
            printf("TRAPPED %ld %s\n", n, name(n));
            trapped++;
        }
    }

    printf("sysprobe: done, %d trapped syscall(s)\n", trapped);
    return 0;
}
