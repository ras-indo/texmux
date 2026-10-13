# PRoot Bootstrap Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace termux-app's native bootstrap (`libtermux-bootstrap.so`) with a PRoot-based system where all shell sessions run inside a Linux rootfs via proot, with proot-loader and minimal rootfs bundled in the APK.

**Architecture:** Two-phase bootstrap: (1) first-run extracts a minimal rootfs tarball from `assets/` and shell scripts to `files/usr/`, (2) terminal sessions execute via `/system/bin/linker64 proot -r <rootfs> -b <mounts> /bin/bash` instead of directly running native bionic binaries. The proot binary and `libproot-loader.so` are prebuilt and embedded in the APK.

**Tech Stack:** Android NDK (native libs), proot (user-space chroot), proot-loader (JNI wrapper .so), minimal Debian/Ubuntu rootfs, Java (existing termux-app codebase)

**Reference Implementation:** Xed-Editor (`com.rk.xededitor`) — key files:
- `MkSession.kt` — session creation with proot env vars
- `ROOTFS.sh` — root invocation with bind mounts
- `setup.sh` — rootfs extraction and configuration
- `init.sh` — container init bashrc
- `ubuntuProcess.kt` — ProcessBuilder-based proot wrapper
- `TerminalFiles.kt` — assets extraction to local bin dir
- `XedConstants.kt` — download URLs for proot/libtalloc/rootfs

---

## File Structure

### Files to MODIFY (existing termux-app files):

| File | Responsibility |
|------|---------------|
| `app/build.gradle` | Remove bootstrap download task, add proot native dep |
| `app/src/main/AndroidManifest.xml` | Simplify permissions to Xed-Editor level |
| `app/src/main/cpp/Android.mk` | Replace `libtermux-bootstrap` with empty/default |
| `app/src/main/java/com/termux/app/TermuxInstaller.java` | Rewrite bootstrap for rootfs extraction |
| `termux-shared/.../TermuxConstants.java` | Update path constants, add ROOTFS/PRoot paths |
| `termux-shared/.../TermuxBootstrap.java` | Remove APT-only variant, simplify |
| `termux-shared/.../TermuxShellEnvironment.java` | Add proot env vars (PROOT_LOADER, PROOT_TMP_DIR, etc.) |
| `termux-shared/.../TermuxAppShellEnvironment.java` | Add PROOT_LOADER/LINKER env vars |
| `termux-shared/.../termux/shell/TermuxShellUtils.java` | Update shell command args for proot |

### Files to CREATE (new):

| File | Responsibility |
|------|---------------|
| `app/src/main/assets/terminal/ROOTFS.sh` | Proot invocation script |
| `app/src/main/assets/terminal/init.sh` | Container init script (bashrc replacement) |
| `app/src/main/assets/terminal/setup.sh` | First-run rootfs setup script |
| `app/src/main/assets/terminal/utils` | Shared shell utility functions |
| `app/src/main/assets/rootfs/rootfs.tar.gz` | Minimal Linux rootfs tarball |
| `app/src/main/jniLibs/<abi>/libproot-loader.so` | Prebuilt proot-loader native lib |
| `app/src/main/jniLibs/<abi>/libtalloc.so.2` | Prebuilt talloc lib for proot |
| `app/src/main/jniLibs/<abi>/proot` | Prebuilt proot binary |

### Files to REMOVE:

| File | Reason |
|------|--------|
| `app/src/main/cpp/termux-bootstrap.c` | No longer needed |
| `app/src/main/cpp/termux-bootstrap-zip.S` | No longer needed |
| `app/src/main/cpp/bootstrap-*.zip` | No longer downloaded |

---

## Task 1: Build System — Proot Native Libraries & Remove Bootstrap

**Files:**
- Modify: `app/build.gradle`
- Modify: `app/src/main/cpp/Android.mk`
- Delete: `app/src/main/cpp/termux-bootstrap.c`
- Delete: `app/src/main/cpp/termux-bootstrap-zip.S`
- Create: `app/src/main/jniLibs/<abi>/libproot-loader.so`
- Create: `app/src/main/jniLibs/<abi>/libtalloc.so.2`
- Create: `app/src/main/jniLibs/<abi>/proot`

**Select ABI:** arm64-v8a (primary), armeabi-v7a, x86_64, x86

- [ ] **Step 1: Remove bootstrap native sources**

```bash
rm app/src/main/cpp/termux-bootstrap.c
rm app/src/main/cpp/termux-bootstrap-zip.S
rm -f app/src/main/cpp/bootstrap-*.zip  # remove any pre-downloaded bootstraps
```

- [ ] **Step 2: Rewrite Android.mk** to build a stub or nothing (empty module)

Write `app/src/main/cpp/Android.mk`:
```makefile
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := libtermux-bootstrap
LOCAL_SRC_FILES := stub.c
include $(BUILD_SHARED_LIBRARY)
```

Create `app/src/main/cpp/stub.c`:
```c
#include <jni.h>
JNIEXPORT jbyteArray JNICALL Java_com_termux_app_TermuxInstaller_getZip(JNIEnv *env, jobject thiz) {
    return NULL;
}
```

- [ ] **Step 3: Create jniLibs directories and place prebuilt proot binaries**

```bash
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  mkdir -p app/src/main/jniLibs/$abi
done
```

Place prebuilt files (these must be obtained from Xed-Editor's build or built from source):
- `app/src/main/jniLibs/arm64-v8a/libproot-loader.so` — from Xed or proot-loader project
- `app/src/main/jniLibs/arm64-v8a/libtalloc.so.2` — from Xed's Karbon-PackagesX
- `app/src/main/jniLibs/arm64-v8a/proot` — static proot binary for aarch64

(You need to do this per ABI. For F-Droid builds, the proot binary is used directly without loader — skip libproot-loader for F-Droid.)

- [ ] **Step 4: Update app/build.gradle** — remove downloadBootstraps dependency

Change `app/build.gradle`:
```groovy
// Remove or comment out the entire downloadBootstrap function
// Remove the downloadBootstraps task
// Remove: afterEvaluate { ... dependsOn(downloadBootstraps) }

// Remove the packageVariant ext block since we no longer use apt bootstrap variants
ext {
    // No packageVariant needed for proot-based builds
}
```

Remove these lines from `app/build.gradle`:
```groovy
// Lines 5-13: Remove entire ext { packageVariant = ... }
// Lines 167-237: Remove downloadBootstrap() and downloadBootstraps task
// Lines 239-242: Remove afterEvaluate { ... }
```

Also remove the dependency on `TERMUX_PACKAGE_VARIANT` in `defaultConfig`:
```groovy
// Remove line 52:
// buildConfigField "String", "TERMUX_PACKAGE_VARIANT", "\"" + project.ext.packageVariant + "\""
```

- [ ] **Step 5: Remove TERMUX_PACKAGE_VARIANT from BuildConfig references**

Search for references to `TERMUX_PACKAGE_VARIANT` and `TERMUX_APP_PACKAGE_VARIANT` in the codebase and plan to update/remove them in Task 3.

- [ ] **Step 6: Verify build compiles**

```bash
cd /home/termux/termux-app
./gradlew app:assembleDebug 2>&1 | tail -20
```
Expected: APK builds successfully without bootstrap download errors.

---

## Task 2: Assets — Rootfs & Shell Scripts

**Files:**
- Create: `app/src/main/assets/terminal/ROOTFS.sh`
- Create: `app/src/main/assets/terminal/init.sh`
- Create: `app/src/main/assets/terminal/setup.sh`
- Create: `app/src/main/assets/terminal/utils`
- Create: `app/src/main/assets/rootfs/` (placeholder, actual rootfs built separately)

- [ ] **Step 1: Create ROOTFS.sh** — the main proot invocation script

Write `app/src/main/assets/terminal/ROOTFS.sh`:
```bash
#!/system/bin/sh
# shellcheck disable=SC2034
force_color_prompt=yes

# PREFIX is set by termux environment to /data/data/com.texmux/files/usr
# Inside proot, -r $PREFIX means / = PREFIX
# So /bin/bash = PREFIX/bin/bash from the rootfs

ARGS="--kill-on-exit"
ARGS="$ARGS -w /"

for system_mnt in /apex /odm /product /system /system_ext /vendor \
 /linkerconfig/ld.config.txt \
 /linkerconfig/com.android.art/ld.config.txt \
 /plat_property_contexts /property_contexts; do

 if [ -e "$system_mnt" ]; then
  system_mnt=$(realpath "$system_mnt")
  ARGS="$ARGS -b ${system_mnt}"
 fi
done
unset system_mnt

# Storage mounts
ARGS="$ARGS -b /sdcard"
ARGS="$ARGS -b /storage"
ARGS="$ARGS -b /dev"
ARGS="$ARGS -b /data"
ARGS="$ARGS -b /dev/urandom:/dev/random"
ARGS="$ARGS -b /proc"
ARGS="$ARGS -b /sys"

# Termux private data
ARGS="$ARGS -b /data/data/com.texmux/files"

if [ -e "/proc/self/fd" ]; then
  ARGS="$ARGS -b /proc/self/fd:/dev/fd"
fi

if [ -e "/proc/self/fd/0" ]; then
  ARGS="$ARGS -b /proc/self/fd/0:/dev/stdin"
fi

if [ -e "/proc/self/fd/1" ]; then
  ARGS="$ARGS -b /proc/self/fd/1:/dev/stdout"
fi

if [ -e "/proc/self/fd/2" ]; then
  ARGS="$ARGS -b /proc/self/fd/2:/dev/stderr"
fi

# Tmpdir inside rootfs
if [ ! -d "$PREFIX/tmp" ]; then
  mkdir -p "$PREFIX/tmp"
  chmod 1777 "$PREFIX/tmp"
fi

ARGS="$ARGS -b $PREFIX/tmp:/dev/shm"

ARGS="$ARGS -r $PREFIX"
ARGS="$ARGS -0"
ARGS="$ARGS --link2symlink"
ARGS="$ARGS --sysvipc"
ARGS="$ARGS -L"

# Execute bash with init script
if [ $# -gt 0 ]; then
  $LINKER $PREFIX/bin/proot $ARGS /bin/bash --rcfile $PREFIX/bin/init -i -c "$*"
else
  $LINKER $PREFIX/bin/proot $ARGS /bin/bash --rcfile $PREFIX/bin/init -i
fi
```

- [ ] **Step 2: Create init.sh** — container init (sourced by bash --rcfile)

Write `app/src/main/assets/terminal/init.sh`:
```bash
# shellcheck disable=SC2034
force_color_prompt=yes
shopt -s checkwinsize

export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export SHELL="bash"
export PS1='\[\e[1;32m\]\u@texmux\[\e[0m\]:\[\e[1;34m\]\w\[\e[0m\] \$ '
export PREFIX=/usr
export HOME=/home

# Source user bashrc if exists
if [[ -f ~/.bashrc ]]; then
    source ~/.bashrc
fi

alias ls='ls --color=auto'
alias grep='grep --color=auto'
alias ll='ls -la'
alias la='ls -A'
```

- [ ] **Step 3: Create setup.sh** — first-run rootfs setup

Write `app/src/main/assets/terminal/setup.sh`:
```bash
#!/system/bin/sh
set -e

# This is called on first run after extracting rootfs
# It sets up the container for first use

SANDBOX_DIR="$PREFIX"

# write hostname
printf '%s\n' "texmux" > "$SANDBOX_DIR/etc/hostname"

# write resolv.conf
nameserver="nameserver 8.8.8.8
nameserver 8.8.4.4"
: > "$SANDBOX_DIR/etc/resolv.conf"
printf '%s\n' "$nameserver" > "$SANDBOX_DIR/etc/resolv.conf"

# write hosts
hosts="127.0.0.1   localhost.localdomain localhost
::1         localhost.localdomain localhost ip6-localhost ip6-loopback"
printf '%s\n' "$hosts" > "$SANDBOX_DIR/etc/hosts"

# Set up android-specific groups
groupFile="$SANDBOX_DIR/etc/group"
aid="$(id -g)"
linesToAdd="
inet:x:3003
everybody:x:9997
android_app:x:20455
"
echo "$linesToAdd" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    gid="${line##*:}"
    case "$(cat "$groupFile" 2>/dev/null)" in
        *:"$gid"*) : ;;
        *) printf '%s\n' "$line" >> "$groupFile" 2>/dev/null || true ;;
    esac
done

# Mark setup as complete
touch "$PREFIX/.texmux_setup_complete"
```

- [ ] **Step 4: Create utils** — shared shell helpers

Write `app/src/main/assets/terminal/utils`:
```bash
#!/system/bin/sh
info() { printf "\033[1;34m[i]\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33m[!]\033[0m %s\n" "$*"; }
error() { printf "\033[1;31m[!]\033[0m %s\n" "$*" >&2; }
```

- [ ] **Step 5: Prepare minimal rootfs tarball**

This step requires BUILDING a minimal rootfs externally (e.g., with debootstrap or docker):
```bash
# BUILD SCRIPT (run on any Linux machine, not in termux):
# debootstrap --variant=minbase --include=ca-certificates,bash,coreutils,apt,dpkg, tar,gzip,curl,wget,sudo,command-not-found \
#   jammy /tmp/texmux-rootfs http://archive.ubuntu.com/ubuntu/
# cd /tmp/texmux-rootfs && tar czf /tmp/rootfs.tar.gz .
# Then copy to: app/src/main/assets/rootfs/rootfs.tar.gz
```

Place a placeholder for now:
Write `app/src/main/assets/rootfs/.gitkeep` (empty file)

---

## Task 3: TermuxInstaller — Rewrite Bootstrap for Rootfs Extraction

**Files:**
- Modify: `app/src/main/java/com/termux/app/TermuxInstaller.java`

Replace `loadZipBytes()` (which loads libtermux-bootstrap) with code that:
1. Extracts rootfs tarball from `assets/rootfs/rootfs.tar.gz` → `$PREFIX`
2. Extracts shell scripts from `assets/terminal/` → `$PREFIX/bin/`
3. Copies proot binary from `jniLibs` or assets → `$PREFIX/bin/proot`
4. Makes binaries executable
5. Runs `setup.sh` via proot for container initialization

- [ ] **Step 1: Rewrite TermuxInstaller.setupBootstrapIfNeeded()**

The key change: instead of loading `libtermux-bootstrap` and extracting a ZIP, we extract the rootfs tarball from assets.

```java
// In TermuxInstaller.java, replace the setupBootstrapIfNeeded method body:

// (1) Same preamble: check files directory, check primary user, check if PREFIX exists
// (2) Instead of loadZipBytes(), do:

private static void extractRootfsFromAssets(Activity activity) throws Exception {
    // Copy proot binary from native lib dir to $PREFIX/bin/
    String nativeLibDir = activity.getApplicationInfo().nativeLibraryDir;
    File prootBinary = new File(nativeLibDir, "proot");
    if (prootBinary.exists()) {
        FileUtils.copyFile("proot binary", prootBinary.getAbsolutePath(),
            TERMUX_PREFIX_DIR_PATH + "/bin/proot");
        Os.chmod(TERMUX_PREFIX_DIR_PATH + "/bin/proot", 0700);
    }
    
    // Extract rootfs tarball from assets
    try (InputStream in = activity.getAssets().open("rootfs/rootfs.tar.gz");
         GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in);
         TarArchiveInputStream tarIn = new TarArchiveInputStream(gzIn)) {
        
        TarArchiveEntry entry;
        while ((entry = tarIn.getNextTarEntry()) != null) {
            File targetFile = new File(TERMUX_STAGING_PREFIX_DIR_PATH, entry.getName());
            // Create directories, extract files, set permissions
            // ... similar to current zip extraction but for tar.gz
        }
    }
    
    // Extract shell scripts from assets/terminal/
    String[] scripts = {"ROOTFS", "init", "setup", "utils"};
    for (String script : scripts) {
        try (InputStream in = activity.getAssets().open("terminal/" + script + ".sh")) {
            String content = new String(FileUtils.readBytes(in));
            Files.write(Paths.get(TERMUX_PREFIX_DIR_PATH + "/bin/" + script), content.getBytes());
            Os.chmod(TERMUX_PREFIX_DIR_PATH + "/bin/" + script, 0700);
        }
    }
}
```

- [ ] **Step 2: Remove System.loadLibrary("termux-bootstrap") call**

Remove `System.loadLibrary("termux-bootstrap");` and the `public static native byte[] getZip();` declaration.

- [ ] **Step 3: Remove bootstrap error dialog references**

Update error handling to reference rootfs extraction errors instead of bootstrap ZIP extraction.

- [ ] **Step 4: Verify bootstrap flow**

```bash
cd /home/termux/termux-app
./gradlew app:assembleDebug
```
Expected: Compilation succeeds, no references to `getZip()` or `libtermux-bootstrap` in the Java code.

---

## Task 4: TermuxConstants — Add Proot Paths

**Files:**
- Modify: `termux-shared/.../TermuxConstants.java`

- [ ] **Step 1: Add rootfs/proot related constants**

Add these path constants to `TermuxConstants.java`:

```java
/** Proot binary installed in prefix bin. */
public static final String TERMUX_PROOT_BIN_PATH = TERMUX_BIN_PREFIX_DIR_PATH + "/proot";

/** Proot tmp directory. */
public static final String TERMUX_PROOT_TMP_DIR_PATH = TERMUX_PREFIX_DIR_PATH + "/proot-tmp";

/** Proot loader .so path - points to nativeLibraryDir. */
// This is resolved dynamically, not a constant

/** Terminal shell scripts directory (inside PREFIX bin). */
public static final String TERMUX_TERMINAL_SCRIPTS_DIR = TERMUX_BIN_PREFIX_DIR_PATH;
```

- [ ] **Step 2: Update TERMUX_PREFIX_DIR** if needed

Current: `TERMUX_PREFIX_DIR_PATH = TERMUX_FILES_DIR_PATH + "/usr"` (= `/data/data/com.texmux/files/usr`)
Keep this as-is — the rootfs will be extracted here.

---

## Task 5: TermuxBootstrap — Simplify for Proot

**Files:**
- Modify: `termux-shared/.../TermuxBootstrap.java`

- [ ] **Step 1: Remove package variant / package manager enums**

Simplify `TermuxBootstrap.java` — since we no longer have APT vs TAPM variants:

```java
// Remove: setTermuxPackageManagerAndVariant()
// Remove: isAppPackageManagerAPT()
// Remove: enum PackageManager
// Remove: enum PackageVariant
// Remove: BUILD_CONFIG_FIELD_TERMUX_PACKAGE_VARIANT
```

Replace with simple constants:
```java
public class TermuxBootstrap {
    public static final String TERMUX_APP_PACKAGE_MANAGER = "apt";  // inside rootfs
    public static final String TERMUX_APP_PACKAGE_VARIANT = "proot";

    public static boolean isProotVariant() {
        return true;
    }
}
```

- [ ] **Step 2: Update all references to TermuxBootstrap methods**

Search for usage of:
- `TermuxBootstrap.isAppPackageVariantAPTAndroid7()` → replace with `TermuxBootstrap.isProotVariant()`
- `TermuxBootstrap.isAppPackageVariantAPTAndroid5()` → remove (no longer applicable)
- `TermuxBootstrap.TERMUX_APP_PACKAGE_VARIANT` → keep for compatibility but set to "proot"
- `TermuxBootstrap.TERMUX_APP_PACKAGE_MANAGER` → keep for compatibility but set to "apt"

---

## Task 6: TermuxShellEnvironment — Add Proot Env Vars

**Files:**
- Modify: `termux-shared/.../TermuxShellEnvironment.java`
- Modify: `termux-shared/.../TermuxAppShellEnvironment.java`

- [ ] **Step 1: Update TermuxShellEnvironment.getEnvironment()**

Add proot-related environment variables before returning:

```java
// After setting PREFIX, HOME, PATH, TMPDIR:

// Proot-specific variables
String nativeLibDir = currentPackageContext.getApplicationInfo().nativeLibraryDir;
environment.put("PROOT_LOADER", nativeLibDir + "/libproot-loader.so");
environment.put("PROOT_TMP_DIR", TermuxConstants.TERMUX_PROOT_TMP_DIR_PATH);
environment.put("LINKER", new File("/system/bin/linker64").exists() ? "/system/bin/linker64" : "/system/bin/linker");
environment.put("SECCOMP", "1");  // enable seccomp by default

// For non-F-Droid (proot-loader available)
// Only set if libproot-loader.so actually exists
if (new File(nativeLibDir, "libproot-loader.so").exists()) {
    environment.put("PROOT_LOADER", nativeLibDir + "/libproot-loader.so");
}

// Set LD_LIBRARY_PATH for proot/talloc
String localLibDir = currentPackageContext.getApplicationInfo().nativeLibraryDir;
environment.put("LD_LIBRARY_PATH", localLibDir + ":" + TermuxConstants.TERMUX_LIB_PREFIX_DIR_PATH);
```

- [ ] **Step 2: Update setupShellCommandArguments()**

Modify `TermuxShellUtils.setupShellCommandArguments()` to wrap commands with ROOTFS.sh when running in a terminal session:

```java
// In TermuxShellUtils.java, update setupShellCommandArguments:
public static String[] setupShellCommandArguments(String executable, String[] arguments) {
    if (TermuxBootstrap.isProotVariant()) {
        // Use ROOTFS.sh wrapper for terminal sessions
        String ROOTFSPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/ROOTFS";
        String[] ROOTFSArgs;
        if (arguments != null && arguments.length > 0) {
            ROOTFSArgs = new String[2 + arguments.length];
            ROOTFSArgs[0] = "-c";
            ROOTFSArgs[1] = ROOTFSPath;
            System.arraycopy(arguments, 0, ROOTFSArgs, 2, arguments.length);
        } else {
            ROOTFSArgs = new String[]{"-c", ROOTFSPath};
        }
        return ROOTFSArgs;
    }
    // Fallback to default
    return arguments;
}
```

Note: This needs careful integration — the shell that spawns the session is `/system/bin/sh` (Android's shell), and the args contain the ROOTFS script path.

---

## Task 7: TerminalSession — Proot-aware Session Creation

**Files:**
- Modify: `termux-shared/.../termux/shell/TermuxShellUtils.java`
- Modify: `termux-shared/.../termux/shell/command/runner/terminal/TermuxSession.java`
- Modify: `app/.../TermuxService.java`

- [ ] **Step 1: Update shell executable in TermuxSession / TermuxService**

When creating a new TerminalSession, the shell executable changes from:
```java
// OLD (native bootstrap):
// executable = TERMUX_PREFIX_DIR_PATH + "/bin/bash"
// arguments = ["-i"]

// NEW (proot-based):
// executable = "/system/bin/sh"
// arguments = ["-c", TERMUX_PREFIX_DIR_PATH + "/bin/ROOTFS"]
```

Find where TerminalSession is constructed in `TermuxSession.java` or `TermuxService.java`:
```java
// Look for code like:
// new TerminalSession(executable, cwd, arguments, env, ...)
```

Change to pass `/system/bin/sh` as shell and ROOTFS script as argument.

- [ ] **Step 2: Update failsafe shell**

For failsafe mode (when prefix is broken), still allow direct shell access:
```java
// Failsafe: use /system/bin/sh directly without proot
// executable = "/system/bin/sh"
// arguments = []
```

---

## Task 8: AndroidManifest — Simplify Permissions

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Remove unnecessary permissions**

Current termux-app permissions:
```xml
ACCESS_NETWORK_STATE, INTERNET, READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE,
MANAGE_EXTERNAL_STORAGE, WAKE_LOCK, VIBRATE, FOREGROUND_SERVICE,
REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, SYSTEM_ALERT_WINDOW, READ_LOGS,
DUMP, WRITE_SECURE_SETTINGS, REQUEST_INSTALL_PACKAGES,
RECEIVE_BOOT_COMPLETED, PACKAGE_USAGE_STATS, SET_ALARM
```

Target (Xed-Editor level):
```xml
INTERNET, POST_NOTIFICATIONS, MANAGE_EXTERNAL_STORAGE (with ignore),
READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_SPECIAL_USE, WAKE_LOCK
```

Remove these permissions:
```xml
<!-- REMOVE -->
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.READ_LOGS" />
<uses-permission android:name="android.permission.DUMP" />
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS" />
<uses-permission android:name="com.android.alarm.permission.SET_ALARM" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Keep but may be optional:
```xml
WAKE_LOCK — keep (needed for foreground service)
FOREGROUND_SERVICE — keep
FOREGROUND_SERVICE_SPECIAL_USE — ADD (Android 14+, matches Xed)
POST_NOTIFICATIONS — ADD (Android 13+, matches Xed)
```

- [ ] **Step 2: Add FOREGROUND_SERVICE_SPECIAL_USE and POST_NOTIFICATIONS**

```xml
<!-- ADD -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

- [ ] **Step 3: Remove sharedUserId if needed (Xed doesn't use it)**

Xed-Editor doesn't use `android:sharedUserId`. If plugin compatibility is not needed, remove:
```xml
<!-- REMOVE from <manifest>:
android:sharedUserId="${TERMUX_PACKAGE_NAME}"
android:sharedUserLabel="@string/shared_user_label"
-->
```

- [ ] **Step 4: Verify manifest compiles**

```bash
cd /home/termux/termux-app
./gradlew app:processDebugManifest 2>&1 | tail -10
```
Expected: Manifest merges successfully.

---

## Task 9: Session Environment — Wire Everything Together

**Files:**
- Modify: `termux-shared/.../TermuxShellUtils.java`
- Modify: `app/.../TermuxService.java`

This is the critical integration task where all components connect.

- [ ] **Step 1: Update TermuxService session creation**

In `TermuxService.java`, find where terminal sessions are created (look for `TerminalSession` constructor calls):

```java
// Inside TermuxService or TermuxSession, modify session parameters:
TerminalSession session = new TerminalSession(
    shellPath,          // "/system/bin/sh"
    workingDir,         // TERMUX_HOME_DIR_PATH
    shellArgs,          // ["-c", "/data/data/com.texmux/files/usr/bin/ROOTFS"]
    environment,        // env array with PROOT_LOADER, PROOT_TMP_DIR, etc.
    terminalBufferSize,
    sessionClient
);
```

- [ ] **Step 2: Handle session re-creation (TermuxService.onStartCommand)**

Ensure that when TermuxService restarts and finds `$PREFIX/bin/ROOTFS` exists, it uses the proot shell instead of the old bash path.

- [ ] **Step 3: Update RUN_COMMAND service**

The `RunCommandService` (which allows other apps to execute commands in termux) also needs proot wrapping. Update `ExecutionCommand` runner to use `/system/bin/sh -c ROOTFS <command>` when the runner type is `TERMINAL_SESSION`.

---

## Task 10: Permission Requests — Runtime Permission Flow

**Files:**
- Modify: `app/.../TermuxActivity.java` (or wherever runtime permission requests happen)

- [ ] **Step 1: Simplify permission request logic**

Xed-Editor requests permissions minimally — just POST_NOTIFICATIONS at runtime:
```kotlin
// Xed Terminal.kt pattern:
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != GRANTED) {
    requestPermission(Manifest.permission.POST_NOTIFICATIONS)
}
```

Update TermuxActivity's permission request flow to match:
- Remove requests for BATTERY_OPTIMIZATIONS, SYSTEM_ALERT_WINDOW, WRITE_SETTINGS, etc.
- Keep storage permission request for MANAGE_EXTERNAL_STORAGE
- Add POST_NOTIFICATIONS permission request for Android 13+

---

## Task 11: Rootfs Building (External)

This task runs OUTSIDE the Android project — on a Linux build machine.

- [ ] **Step 1: Build minimal rootfs**

```bash
# On a Linux machine (or GitHub CI):
ROOTFS_DIR=/tmp/texmux-rootfs
mkdir -p $ROOTFS_DIR

# Use debootstrap to create Ubuntu 22.04 minimal rootfs
sudo debootstrap --variant=minbase \
  --include=apt,apt-utils,bash,coreutils,dpkg,tar,gzip,ca-certificates,\
curl,wget,sudo,command-not-found,less,nano,ncurses-base,\
netbase,openssh-client,passwd,ubuntu-keyring \
  --arch=arm64 \
  jammy $ROOTFS_DIR http://ports.ubuntu.com/ubuntu-ports/

# Clean up
sudo chroot $ROOTFS_DIR apt-get clean
sudo rm -rf $ROOTFS_DIR/var/cache/apt/archives/*
sudo rm -rf $ROOTFS_DIR/var/lib/apt/lists/*

# Create tarball
sudo tar czf /tmp/rootfs-arm64.tar.gz -C $ROOTFS_DIR .

# Copy to Android project
cp /tmp/rootfs-arm64.tar.gz app/src/main/assets/rootfs/rootfs.tar.gz
```

Repeat for armhf and amd64 if supporting multiple architectures.

---

## Verification Checklist

- [ ] APK builds without bootstrap download (`./gradlew app:assembleDebug`)
- [ ] APK contains libproot-loader.so in lib/<abi>/ directories
- [ ] APK contains rootfs.tar.gz in assets/rootfs/
- [ ] APK contains ROOTFS.sh, init.sh, utils in assets/terminal/
- [ ] On first run, rootfs is extracted to $PREFIX
- [ ] Shell scripts are extracted to $PREFIX/bin/
- [ ] Terminal session starts via `/system/bin/sh -c ROOTFS`
- [ ] Bash prompt shows inside the rootfs (glibc)
- [ ] apt update/install works inside the container
- [ ] Storage access (/sdcard) works inside proot
- [ ] Permission requests match Xed-Editor level
- [ ] Failsafe mode works (broken prefix → `/system/bin/sh` directly)
- [ ] `lsp_diagnostics` clean on all modified Java files

---

## Rollback Plan

If the PRoot migration fails or has critical issues:

1. Revert `app/build.gradle` to restore bootstrap download task
2. Restore `app/src/main/cpp/` (termux-bootstrap.c, Android.mk)
3. Restore `TermuxInstaller.java` to original
4. Restore `AndroidManifest.xml` permissions
5. Clear `assets/terminal/` and `assets/rootfs/`
6. Clear `jniLibs/` directory
7. Restore `TermuxConstants.java`, `TermuxShellEnvironment.java`, `TermuxBootstrap.java`
8. Build and verify: `./gradlew clean app:assembleDebug`

---

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Proot performance slower than native | Medium | Medium | Use `--sysvipc -L --link2symlink` optimizations; benchmark |
| Proot-loader .so unavailable for some ABIs | Medium | High | Include static proot binary fallback (F-Droid mode) |
| Rootfs too large for APK (>100MB) | High | Medium | Use debootstrap minbase; strip unnecessary packages; use xz compression |
| Plugin compatibility broken (Termux:API) | High | High | Plugin commands need to be wrapped to run inside proot or via host |
| SELinux restrictions blocking proot | Low | High | Xed already works on Android 10-14; libproot-loader handles SELinux |
| Rootfs needs regular updates | Medium | Low | Include apt in rootfs; user runs apt update/upgrade themselves |
