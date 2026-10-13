#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
SELF="$(basename "$0")"

# ── module definitions (from build.gradle + AGENTS.md) ──────────────
MODULES=(app termux-shared terminal-emulator terminal-view termux-api)

# Per-module tasks known from build.gradle
declare -A MODULE_TASKS
MODULE_TASKS[app]="assembleDebug assembleRelease versionName buildTermuxApiBinary buildTermuxAmNative copyTermuxApiApk"
MODULE_TASKS[termux-shared]="assembleDebug assembleRelease sourceJar"
MODULE_TASKS[terminal-emulator]="assembleDebug assembleRelease sourceJar"
MODULE_TASKS[terminal-view]="assembleDebug assembleRelease sourceJar"
MODULE_TASKS[termux-api]="assembleDebug assembleRelease versionName"
COMMON_TASKS="clean compileDebugJavaWithJavac compileReleaseJavaWithJavac test lint"

# ── defaults ───────────────────────────────────────────────────────
MODULE="app"
TASK=""
CLEAN=false
RELEASE=false
JOBS=2
PARALLEL=""
HELP=false

list_modules() {
    echo "Available modules:"
    for m in "${MODULES[@]}"; do
        echo "    $m"
    done
    echo ""
    echo "Use:  ./$SELF -m <module>  to build a module"
    echo "      ./$SELF list_task    to see tasks per module"
    exit 0
}

# ── helpers ─────────────────────────────────────────────────────────
usage() {
    cat <<EOF
Usage: ./$SELF [options]

Default: debug APK build (:app:assembleDebug, 2 workers)

╔══════════════════════════════════════════════════════════╗
║  Modules                                               ║
╠══════════════════════════════════════════════════════════╣
║  app                main Termux app              (:app) ║
║  termux-shared      shared Android library              ║
║  terminal-emulator  terminal emulator core              ║
║  terminal-view      terminal view widget                ║
║  termux-api         Termux:API plugin                   ║
╚══════════════════════════════════════════════════════════╝

Options:
  -m, --module <m>   Build module by name (default: app)
  -t, --task <t>     Run exact Gradle task (overrides -m)
  list_task          Show available tasks per module (from build.gradle data)
  list_modules       List available modules only
  -c, --clean        Clean before build
  -r, --release      Release build
  -j, --jobs <N>     Max parallel workers (default: 2)
      --parallel     Enable Gradle parallel execution
      --no-parallel  Disable Gradle parallel execution
  -h, --help         This help

Examples:
  ./$SELF                          # debug :app, 2 workers
  ./$SELF -c                       # clean + debug :app
  ./$SELF -r                       # release :app
  ./$SELF -t :app:buildTermuxAmNative  # run that exact task
  ./$SELF -t :termux-shared:compileDebugJavaWithJavac
  ./$SELF list_task                # show tasks from build.gradle data
  ./$SELF -j 8 --parallel          # 8 workers + parallel
  ./$SELF --no-parallel            # sequential
EOF
    exit 0
}

die() { echo -e "\e[31mERROR:\e[0m $*" >&2; exit 1; }
info()  { echo -e "\e[36m==>\e[0m $*"; }
ok()    { echo -e "\e[32m✓\e[0m $*"; }

list_tasks() {
    echo "Available tasks per module (from build.gradle data):"
    echo ""
    for m in "${MODULES[@]}"; do
        echo "  :$m"
        for t in ${MODULE_TASKS[$m]} $COMMON_TASKS; do
            echo "      :${m}:${t}"
        done
        echo ""
    done
    echo "  clean  (root project clean)"
    echo ""
    echo "Note: Gradle may expose additional tasks at runtime."
    echo "Run './gradlew tasks' for the full dynamic list."
    exit 0
}

# ── no args → show help ──────────────────────────────────────────────
[[ $# -eq 0 ]] && usage

# ── parse args ──────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)      HELP=true ;;
        list_task)      list_tasks ;;
        list_modules)   list_modules ;;
        -c|--clean)     CLEAN=true ;;
        -r|--release)   RELEASE=true ;;
        -m|--module)    shift; MODULE="$1" ;;
        -t|--task)      shift; TASK="$1" ;;
        -j|--jobs)      shift; JOBS="$1" ;;
        --parallel)     PARALLEL="--parallel" ;;
        --no-parallel)  PARALLEL="--no-parallel" ;;
        *)              die "Unknown option: $1. Try ./$SELF -h" ;;
    esac
    shift
done

$HELP && usage

# validate module
VALID=false
for m in "${MODULES[@]}"; do [[ "$m" == "$MODULE" ]] && { VALID=true; break; }; done
$VALID || die "Invalid module '$MODULE'. Valid: ${MODULES[*]}"

# ── resolve gradle task ─────────────────────────────────────────────
if [[ -n "$TASK" ]]; then
    GRADLE_TASK="$TASK"
elif $RELEASE; then
    GRADLE_TASK=":${MODULE}:assembleRelease"
else
    GRADLE_TASK=":${MODULE}:assembleDebug"
fi

# ── compose gradle args ────────────────────────────────────────────
GRADLE_ARGS=("$GRADLE_TASK" "--stacktrace")

if [[ "$JOBS" =~ ^[0-9]+$ ]] && [[ "$JOBS" -gt 0 ]]; then
    GRADLE_ARGS+=("--max-workers=$JOBS")
else
    die "--jobs requires a positive integer, got '$JOBS'"
fi

[[ -n "$PARALLEL" ]] && GRADLE_ARGS+=("$PARALLEL")

# ── print build info ────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║  termux-app build                                   ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  Task:      $GRADLE_TASK"
echo "║  Clean:     $([[ $CLEAN == true ]] && echo 'yes' || echo 'no')"
echo "║  Workers:   $JOBS"
echo "║  Parallel:  ${PARALLEL:-default}"
echo "║  Java:      $(java -version 2>&1 | head -1)"
echo "║  SDK:       $(ls "${ANDROID_SDK_ROOT:-/opt/android-sdk}"/platforms/ 2>/dev/null | tr '\n' ' ')"
echo "║  NDK:       $(ls "${ANDROID_SDK_ROOT:-/opt/android-sdk}"/ndk/ 2>/dev/null | tr '\n' ' ')"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# ── clean ────────────────────────────────────────────────────────────
if $CLEAN; then
    info "Clean …"
    if [[ -n "$TASK" ]]; then
        CM=":$(echo "$TASK" | cut -d: -f2)"
        [[ -n "$CM" ]] && ./gradlew "${CM}:clean" --stacktrace 2>&1 || true
    else
        ./gradlew ":${MODULE}:clean" --stacktrace 2>&1
    fi
    ok "Clean done"
    echo ""
fi

export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"

# ── native (C/C++) compilation parallel control ─────────────────────
# CMake uses CMAKE_BUILD_PARALLEL_LEVEL; ndk-build uses NDK_BUILD_PARALLEL
export CMAKE_BUILD_PARALLEL_LEVEL="$JOBS"
export NDK_BUILD_PARALLEL="$JOBS"

info "Running: ./gradlew $GRADLE_TASK"
echo ""
./gradlew "${GRADLE_ARGS[@]}" 2>&1
rc=$?

if [[ $rc -ne 0 ]]; then
    echo ""
    echo -e "\e[31m=== BUILD FAILED (exit $rc) ===\e[0m"
    exit $rc
fi

# ── show APK path ───────────────────────────────────────────────────
echo ""
SUFFIX="debug"
$RELEASE && SUFFIX="release"

if [[ -z "$TASK" || "$TASK" == *assemble* ]]; then
    FOUND=$(find "$MODULE" -name "*.apk" -path "*/${SUFFIX}/*" -type f 2>/dev/null | sort || true)
    if [[ -n "$FOUND" ]]; then
        info "APK output:"
        echo "$FOUND"
        echo ""
        info "Install:"
        echo "  cp $(echo "$FOUND" | head -1) /sdcard/"
        echo "  am start -a android.intent.action.VIEW \\"
        echo "    -d file://$(echo "$FOUND" | head -1) \\"
        echo "    -t application/vnd.android.package-archive"
    fi
fi

echo ""
ok "Build done: $GRADLE_TASK"
