#!/usr/bin/env bash
#
# rename-package.sh — Full package rename for the Termux fork.
#
# Handles BOTH prefix families present in this (hybrid) repo:
#   - "com.texmux"  : Gradle namespace (R class + applicationId), TERMUX_PACKAGE_NAME,
#                     termux-api Java package, hardcoded runtime strings.
#   - "com.termux"  : Java packages for app/shared/view/terminal/filepicker + manifest
#                     component references (android:name="com.termux.app.*").
#
# What it changes:
#   1. applicationId / namespace in every build.gradle
#   2. manifestPlaceholders.TERMUX_PACKAGE_NAME
#   3. TermuxConstants.TERMUX_PACKAGE_NAME  (source of truth -> data dir, RUN_COMMAND,
#      socket path, plugin package names all derive from it)
#   4. Hardcoded runtime strings that DON'T use the constant (AmSocketServer, NativeBridge,
#      TermuxInstaller apps/ path, strings.xml paths)
#   5. Every Java package declaration + import + R import + manifest android:name + xml refs
#   6. Moves the source folder com/{termux,texmux} -> com/<new> in every module (git mv)
#
# It does NOT touch: .git/, */build/, docs/, or any *_URL constant that is DERIVED from
# TERMUX_PACKAGE_NAME (those update automatically).
#
# Usage:
#   ./rename-package.sh <new-package> [--dry-run]
#     e.g. ./rename-package.sh com.example.termux
#          ./rename-package.sh com.termux --dry-run
#
set -euo pipefail

NEW="${1:-}"
DRY="${2:-}"

if [[ -z "$NEW" ]]; then
  echo "usage: $0 <new-package> [--dry-run]" >&2
  exit 2
fi
# Validate package: dotted, lowercase alnum segments, starts with letter.
if [[ ! "$NEW" =~ ^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$ ]]; then
  echo "ERROR: '$NEW' is not a valid Android package name (need e.g. com.example.app)" >&2
  exit 2
fi

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

# --- Source of truth: current base package from TermuxConstants -----------------
CUR=$(grep -oP 'TERMUX_PACKAGE_NAME\s*=\s*"\K[^"]+' \
      termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java \
      | head -1)
if [[ -z "$CUR" ]]; then
  echo "ERROR: could not detect current TERMUX_PACKAGE_NAME from TermuxConstants.java" >&2
  exit 1
fi

echo "Current base package : $CUR"
echo "New      base package: $NEW"
if [[ "$CUR" == "$NEW" ]]; then
  echo "Nothing to do — already $NEW."
  exit 0
fi

# Prefix families to rewrite. Always fold the legacy Java prefix "com.termux" and the
# current base "$CUR". If they're equal we just use one. Order matters: longest first so
# e.g. com.texmux.api is handled by the plain com.texmux rule (dot boundary does the rest).
PREFIXES=()
add_prefix() { local p="$1"; for e in "${PREFIXES[@]-}"; do [[ "$e" == "$p" ]] && return; done; PREFIXES+=("$p"); }
add_prefix "$CUR"
add_prefix "com.texmux"
add_prefix "com.termux"
echo "Rewriting prefixes   : ${PREFIXES[*]}"

# New source-folder leaf (third segment): com.<leaf>
NEW_LEAF="${NEW#com.}"
NEW_LEAF="${NEW_LEAF%%.*}"

# --- File types to rewrite text in ---------------------------------------------
# (excluded: .git, build outputs, docs, this script, and the .apk/binaries)
mapfile -t FILES < <(
  git ls-files \
    -- '*.java' '*.xml' '*.gradle' '*.properties' '*.pro' '*.md' \
    | grep -v '^docs/' \
    | xargs -r grep -lI -E "$(printf '%s|' "${PREFIXES[@]}" | sed 's/|$//; s/\./\\./g')" 2>/dev/null || true
)
echo "Files to rewrite     : ${#FILES[@]}"

run() { if [[ "$DRY" == "--dry-run" ]]; then echo "  DRY: $*"; else "$@"; fi; }

# --- 1) Text replacement --------------------------------------------------------
# Single word-boundary pass per prefix. \b ensures we match com.texmux inside
# com.texmux.api and standalone com.texmux, but NOT longer ids like com.texmuxfoo.
# (Delimited with # so the regex's own | alternation never collides with the delimiter.)
for P in "${PREFIXES[@]}"; do
  PE=$(printf '%s' "$P" | sed 's/[.[\*^$]/\\&/g')   # escape regex metachars
  for f in "${FILES[@]}"; do
    if grep -qI -w "$P" "$f" 2>/dev/null; then
      run sed -i "s#\b${PE}\b#${NEW}#g" "$f"
    fi
  done
done

# --- 2) Move source folders (com/<oldleaf> -> com/<newleaf>) --------------------
# Find every com/<leaf> source dir across modules; old leaves = termux, texmux, cur leaf.
CUR_LEAF="${CUR#com.}"; CUR_LEAF="${CUR_LEAF%%.*}"
OLD_LEAVES=("termux" "texmux" "$CUR_LEAF")
declare -A SEEN=()
for leaf in "${OLD_LEAVES[@]}"; do
  [[ "$leaf" == "$NEW_LEAF" ]] && continue
  [[ -n "${SEEN[$leaf]:-}" ]] && continue
  SEEN[$leaf]=1
  while IFS= read -r d; do
    parent="$(dirname "$d")"          # .../java/com
    target="$parent/$NEW_LEAF"
    if [[ -d "$target" ]]; then
      # Merge: move children then drop old dir.
      echo "  merge $d -> $target"
      for child in "$d"/*; do
        base="$(basename "$child")"
        if [[ -e "$target/$base" ]]; then
          echo "    WARN: $target/$base exists, merging contents"
          run cp -a "$child/." "$target/$base/" 2>/dev/null || run mv "$child" "$target/$base/"
        else
          run git mv "$child" "$target/$base" 2>/dev/null || run mv "$child" "$target/$base"
        fi
      done
      run rm -rf "$d"
    else
      echo "  move $d -> $target"
      run git mv "$d" "$target" 2>/dev/null || run mv "$d" "$target"
    fi
  done < <(find . -type d -path "*/java/com/$leaf" -not -path "./.git/*" -not -path "*/build/*")
done

# --- 3) Clean now-empty com/<old> dirs -----------------------------------------
for leaf in "${OLD_LEAVES[@]}"; do
  [[ "$leaf" == "$NEW_LEAF" ]] && continue
  find . -type d -path "*/java/com/$leaf" -not -path "./.git/*" -not -path "*/build/*" -empty -delete 2>/dev/null || true
done

echo ""
echo "=== Verify: leftover OLD prefixes in tracked source ==="
# Only flag prefixes that are NOT the new name — otherwise the target itself (e.g.
# com.termux when renaming to com.termux) would false-positive on every clean file.
LEFTOVER_RE=""
for P in "${PREFIXES[@]}"; do
  [[ "$P" == "$NEW" ]] && continue
  PE=$(printf '%s' "$P" | sed 's/[.[\*^$]/\\&/g')
  [[ -n "$LEFTOVER_RE" ]] && LEFTOVER_RE="${LEFTOVER_RE}|"
  LEFTOVER_RE="${LEFTOVER_RE}${PE}"
done
if [[ -n "$LEFTOVER_RE" ]]; then
  LEFTOVER=$(git ls-files -- '*.java' '*.xml' '*.gradle' '*.properties' '*.pro' \
    | grep -v '^docs/' \
    | xargs -r grep -lI -E "\b(${LEFTOVER_RE})\b" 2>/dev/null || true)
  if [[ -n "$LEFTOVER" ]]; then
    echo "$LEFTOVER"
    echo "(review these — may be legit references to upstream Termux)"
  else
    echo "none — clean."
  fi
else
  echo "n/a — only the new name is in scope."
fi

echo ""
echo "Done. New package: $NEW"
if [[ "$DRY" == "--dry-run" ]]; then
  echo "(dry run — no changes written)"
else
  echo "Next: build & test.  e.g. trigger CI, or ./gradlew assembleDebug"
fi
