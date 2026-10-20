/*
 * sysprobe — freestanding aarch64 probe for Android seccomp traps.
 *
 * No libc at all: every syscall is issued with a raw `svc #0`. A static
 * bionic binary aborts before it can report anything (its own startup hits
 * the very policy we are measuring), so this one has no startup path to
 * break: _start is the entry point and the only helpers are register-based
 * syscall stubs.
 *
 * It calls each syscall number in a forked child with all-zero arguments and
 * prints `TRAPPED <nr> <name>` for every child that dies with SIGSYS (31 on
 * arm64) — i.e. exactly what Android's app seccomp policy refuses. That
 * policy is inherited by every child of the app, proot's tracees included.
 *
 * Safety: children are polled with WNOHANG and killed after a bounded spin,
 * so a blocking syscall cannot hang the probe; clone/exit/clone3/ptrace and
 * the signal-mask syscalls are skipped so the probe cannot fork or suspend
 * itself.
 *
 * Build: aarch64-linux-android24-clang -static -nostdlib -fno-stack-protector
 *        -O2 -Wl,-e,_start -o sysprobe sysprobe.c
 */

typedef long i64;
typedef unsigned long u64;

#define SYS_write       64
#define SYS_clone      220
#define SYS_wait4      260
#define SYS_kill       129
#define SYS_exit_group  94

#define SIGCHLD  17
#define SIGKILL   9

#define WNOHANG   1
#define WTERMSIG(s)  ((s) & 0x7f)
#define WIFSIGNALED(s) (((s) & 0x7f) != 0x7f && ((s) & 0x7f) != 0)

static inline i64 sc6(i64 n, i64 a, i64 b, i64 c, i64 d, i64 e, i64 f);
static inline i64 sc6(i64 n, i64 a, i64 b, i64 c, i64 d, i64 e, i64 f)
{
    register i64 x8 asm("x8") = n;
    register i64 x0 asm("x0") = a;
    register i64 x1 asm("x1") = b;
    register i64 x2 asm("x2") = c;
    register i64 x3 asm("x3") = d;
    register i64 x4 asm("x4") = e;
    register i64 x5 asm("x5") = f;
    asm volatile("svc #0"
                 : "+r"(x0)
                 : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5)
                 : "memory");
    return x0;
}

#define sc(n, a, b, c, d, e, f) sc6((n), (i64)(a), (i64)(b), (i64)(c), (i64)(d), (i64)(e), (i64)(f))

static u64 slen(const char *s)
{
    u64 n = 0;
    while (s[n]) n++;
    return n;
}

static void outs(const char *s)
{
    sc(SYS_write, 1, s, slen(s), 0, 0, 0);
}

static void outn(i64 v)
{
    char buf[24];
    int i = 23;
    int neg = 0;
    u64 u;
    if (v < 0) { neg = 1; u = (u64)(-v); } else u = (u64)v;
    buf[i--] = '\0';
    if (u == 0) buf[i--] = '0';
    while (u > 0) { buf[i--] = (char)('0' + (u % 10)); u /= 10; }
    if (neg) buf[i--] = '-';
    outs(&buf[i + 1]);
}

static const char *name(i64 n)
{
    switch (n) {
    case 220: return "clone";
    case 221: return "execve";
    case 260: return "wait4";
    case 278: return "getrandom";
    case 293: return "rseq";
    case 435: return "clone3";
    case 437: return "openat2";
    case 439: return "faccessat2";
    case 440: return "process_madvise";
    case 441: return "epoll_pwait2";
    case 442: return "mount_setattr";
    case 447: return "memfd_secret";
    case 448: return "process_mrelease";
    case 449: return "futex_waitv";
    case 450: return "set_mempolicy_home_node";
    default:  return "";
    }
}

static int skip(i64 n)
{
    switch (n) {
    case 93:  /* exit              */
    case 94:  /* exit_group        */
    case 117: /* ptrace (TRACEME would stop the child) */
    case 128: /* restart_syscall   */
    case 130: /* rt_sigtimedwait (blocks) */
    case 139: /* rt_sigreturn      */
    case 178: /* rt_sigsuspend (blocks)  */
    case 220: /* clone             */
    case 260: /* wait4 (would reap the probe's own children) */
    case 435: /* clone3            */
        return 1;
    default:
        return 0;
    }
}

void _start(void)
{
    i64 n;
    i64 trapped = 0;

    outs("sysprobe(freestanding): scanning 0..460\n");

    for (n = 0; n <= 460; n++) {
        i64 pid, st, tries;
        if (skip(n)) continue;

        pid = sc(SYS_clone, SIGCHLD, 0, 0, 0, 0, 0);
        if (pid == 0) {
            sc(n, 0, 0, 0, 0, 0, 0);
            sc(SYS_exit_group, 0, 0, 0, 0, 0, 0);
            for (;;) { }
        }
        if (pid < 0) continue;

        st = 0;
        for (tries = 0; tries < 200000; tries++) {
            i64 r = sc(SYS_wait4, pid, &st, WNOHANG, 0, 0, 0);
            if (r == pid) break;
            if (r < 0) { tries = 200000; break; }
        }
        if (tries >= 200000) {
            sc(SYS_kill, pid, SIGKILL, 0, 0, 0, 0);
            st = 0;
            sc(SYS_wait4, pid, &st, 0, 0, 0, 0);
        }

        if (WIFSIGNALED(st) && WTERMSIG(st) == 31) {
            outs("TRAPPED ");
            outn(n);
            outs(" ");
            outs(name(n));
            outs("\n");
            trapped++;
        }
    }

    outs("sysprobe: done, ");
    outn(trapped);
    outs(" trapped syscall(s)\n");

    sc(SYS_exit_group, 0, 0, 0, 0, 0, 0);
    for (;;) { }
}
