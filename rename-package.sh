#!/usr/bin/env bash
#
# rename-package.sh — Full package rename for the Termux fork.
#
# The repo uses the package name in MULTIPLE forms across MULTIPLE file types:
#   - DOT form      com.texmux / com.termux   (gradle, java, xml, cmake, shell, properties)
#   - UNDERSCORE    com_texmux / com_termux   (JNI symbol names: Java_com_texmux_api_*)
#   - SLASH path    com/texmux / com/termux   (source folders + some native string paths)
# It also has BOTH prefix families in a hybrid state: com.texmux (namespace/
# applicationId/TERMUX_PACKAGE_NAME/termux-api) and com.termux (Java packages for
# app/shared/view/terminal/filepicker + manifest component refs).
#
# The script rewrites BOTH prefix families AND both dot/underscore forms across ALL
# tracked text files (no extension filter — this catches .c, .sh, CMakeLists.txt, .in,
# extensionless scripts, etc.), then git-moves the com/{texmux,termux} source folders.
#
# It does NOT touch: .git/, */build/, docs/, or *_URL constants DERIVED from
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
if [[ ! "$NEW" =~ ^[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)+$ ]]; then
  echo "ERROR: '$NEW' is not a valid Android package name (need e.g. com.example.app)" >&2
  exit 2
fi

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

# --- Source of truth: current base package from TermuxConstants -----------------
TC_FILE="termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java"
if [[ ! -f "$TC_FILE" ]]; then
  # After a previous rename the path leaf may differ; find it.
  TC_FILE="$(git ls-files | grep 'TermuxConstants.java$' | head -1 || true)"
fi
CUR=$(grep -oP 'TERMUX_PACKAGE_NAME\s*=\s*"\K[^"]+' "$TC_FILE" | head -1)
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

# Prefix families to rewrite. Fold the legacy Java prefix "com.termux" and current base.
PREFIXES=()
add_prefix() { local p="$1"; for e in "${PREFIXES[@]-}"; do [[ "$e" == "$p" ]] && return; done; PREFIXES+=("$p"); }
add_prefix "$CUR"
add_prefix "com.texmux"
add_prefix "com.termux"
echo "Rewriting prefixes   : ${PREFIXES[*]}"

# Derived forms for the NEW package (underscore variant for JNI, leaf for folder move).
NEW_US="${NEW//./_}"
NEW_LEAF="${NEW#com.}"; NEW_LEAF="${NEW_LEAF%%.*}"
CUR_LEAF="${CUR#com.}"; CUR_LEAF="${CUR_LEAF%%.*}"

run() { if [[ "$DRY" == "--dry-run" ]]; then echo "  DRY: $*"; else "$@"; fi; }

# --- Build the set of tracked TEXT files containing any prefix (dot or underscore) ---
# No extension filter: grep -I skips binaries, so this catches .c/.sh/.in/CMakeLists/
# extensionless scripts too. Excludes docs/ and this script.
collect_files() {
  local pat=""
  for P in "${PREFIXES[@]}"; do
    local us="${P//./_}"
    local pe="$(printf '%s' "$P" | sed 's/[.[\*^$]/\\&/g')"
    local ue="$(printf '%s' "$us" | sed 's/[_[\*^$]/\\&/g')"
    [[ -n "$pat" ]] && pat="${pat}|"
    pat="${pat}${pe}|${ue}"
  done
  git ls-files \
    | grep -v '^docs/' \
    | grep -v '^rename-package\.sh$' \
    | xargs -r grep -lIE "\b(${pat})\b" 2>/dev/null || true
}

mapfile -t FILES < <(collect_files)
echo "Files to rewrite     : ${#FILES[@]}"

# --- 1) Text replacement: dot form AND underscore form, per prefix ----------------
for P in "${PREFIXES[@]}"; do
  PE="$(printf '%s' "$P" | sed 's/[.[\*^$]/\\&/g')"
  for f in "${FILES[@]}"; do
    # dot form: com.texmux -> com.new
    grep -qI -w "$P" "$f" 2>/dev/null && run sed -i "s#\b${PE}\b#${NEW}#g" "$f" || true
  done
  # underscore form: com_texmux -> com_new (JNI symbols)
  US="${P//./_}"
  UE="$(printf '%s' "$US" | sed 's/[_[\*^$]/\\&/g')"
  for f in "${FILES[@]}"; do
    grep -qI -w "$US" "$f" 2>/dev/null && run sed -i "s#\b${UE}\b#${NEW_US}#g" "$f" || true
  done
done

# --- 2) Move source folders (com/<oldleaf> -> com/<newleaf>) ----------------------
OLD_LEAVES=("termux" "texmux" "$CUR_LEAF")
declare -A SEEN=()
for leaf in "${OLD_LEAVES[@]}"; do
  [[ "$leaf" == "$NEW_LEAF" ]] && continue
  [[ -n "${SEEN[$leaf]:-}" ]] && continue
  SEEN[$leaf]=1
  while IFS= read -r d; do
    parent="$(dirname "$d")"
    target="$parent/$NEW_LEAF"
    if [[ -d "$target" ]]; then
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

# --- 3) Clean now-empty com/<old> dirs -------------------------------------------
for leaf in "${OLD_LEAVES[@]}"; do
  [[ "$leaf" == "$NEW_LEAF" ]] && continue
  find . -type d -path "*/java/com/$leaf" -not -path "./.git/*" -not -path "*/build/*" -empty -delete 2>/dev/null || true
done

# --- Verify: no leftover OLD prefixes (dot OR underscore) in tracked source -------
echo ""
echo "=== Verify: leftover OLD prefixes in tracked source ==="
LEFTOVER_RE=""
for P in "${PREFIXES[@]}"; do
  [[ "$P" == "$NEW" ]] && continue
  PE="$(printf '%s' "$P" | sed 's/[.[\*^$]/\\&/g')"
  US="${P//./_}"
  UE="$(printf '%s' "$US" | sed 's/[_[\*^$]/\\&/g')"
  [[ -n "$LEFTOVER_RE" ]] && LEFTOVER_RE="${LEFTOVER_RE}|"
  LEFTOVER_RE="${LEFTOVER_RE}${PE}|${UE}"
done
if [[ -n "$LEFTOVER_RE" ]]; then
  LEFTOVER=$(git ls-files \
    | grep -v '^docs/' \
    | grep -v '^rename-package\.sh$' \
    | xargs -r grep -lIE "\b(${LEFTOVER_RE})\b" 2>/dev/null || true)
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
