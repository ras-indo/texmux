#!/usr/bin/env bash
#
# rename-package.sh — Full package rename for the Termux fork.
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

TC_FILE="$(git ls-files | grep 'TermuxConstants.java$' | head -1 || true)"
if [[ -z "$TC_FILE" || ! -f "$TC_FILE" ]]; then
  TC_FILE="$(find . -name 'TermuxConstants.java' -not -path '*/build/*' -not -path '*/.git/*' | head -1)"
fi
if [[ -z "$TC_FILE" || ! -f "$TC_FILE" ]]; then
  echo "ERROR: could not find TermuxConstants.java" >&2
  exit 1
fi

CUR=$(grep -oP 'TERMUX_PACKAGE_NAME\s*=\s*"\K[^"]+' "$TC_FILE" | head -1)
if [[ -z "$CUR" ]]; then
  echo "ERROR: could not detect current TERMUX_PACKAGE_NAME from $TC_FILE" >&2
  exit 1
fi

echo "Current base package : $CUR"
echo "New      base package: $NEW"
if [[ "$CUR" == "$NEW" ]]; then
  echo "Nothing to do — already $NEW."
  exit 0
fi

PREFIXES=()
add_prefix() { local p="$1"; for e in "${PREFIXES[@]-}"; do [[ "$e" == "$p" ]] && return; done; PREFIXES+=("$p"); }
add_prefix "$CUR"
add_prefix "com.termux"
add_prefix "com.texmux"

echo "Rewriting prefixes   : ${PREFIXES[*]}"

NEW_SLASH="${NEW//./\/}"
NEW_US="${NEW//./_}"

run() { if [[ "$DRY" == "--dry-run" ]]; then echo "  DRY: $*"; else "$@"; fi; }

collect_files() {
  local pat=""
  for P in "${PREFIXES[@]}"; do
    local us="${P//./_}"
    local pe ue
    pe="$(printf '%s' "$P" | sed 's/[.[\\*^$]/\\&/g')"
    ue="$(printf '%s' "$us" | sed 's/[_[\\*^$]/\\&/g')"
    [[ -n "$pat" ]] && pat="${pat}|"
    pat="${pat}${pe}|${ue}"
  done
  git ls-files 2>/dev/null | grep -v '^docs/' | grep -v '^rename-package\.sh$' | grep -v '/build/' | xargs -r grep -lIE "\b(${pat})\b" 2>/dev/null || true
}

mapfile -t FILES < <(collect_files)
echo "Files to rewrite     : ${#FILES[@]}"

for P in "${PREFIXES[@]}"; do
  [[ "$P" == "$NEW" ]] && continue
  PE="$(printf '%s' "$P" | sed 's/[.[\\*^$]/\\&/g')"
  for f in "${FILES[@]}"; do
    [[ ! -f "$f" ]] && continue
    if grep -qI "\b${P}\b" "$f" 2>/dev/null; then
      run sed -i "s#\b${PE}\b#${NEW}#g" "$f"
    fi
  done
  US="${P//./_}"
  [[ "$US" == "$NEW_US" ]] && continue
  UE="$(printf '%s' "$US" | sed 's/[_[\\*^$]/\\&/g')"
  for f in "${FILES[@]}"; do
    [[ ! -f "$f" ]] && continue
    if grep -qI "\b${US}\b" "$f" 2>/dev/null; then
      run sed -i "s#\b${UE}\b#${NEW_US}#g" "$f"
    fi
  done
  PS="${P//./\/}"
  NEW_SLASH_LOCAL="${NEW//./\/}"
  [[ "$PS" == "$NEW_SLASH_LOCAL" ]] && continue
  for f in "${FILES[@]}"; do
    [[ ! -f "$f" ]] && continue
    if grep -qF "$PS" "$f" 2>/dev/null; then
      run sed -i "s#${PS}#${NEW_SLASH_LOCAL}#g" "$f"
    fi
  done
done

OLD_PACKAGES=("$CUR" "com.termux" "com.texmux")
NEW_SLASH="${NEW//./\/}"
for oldpkg in "${OLD_PACKAGES[@]}"; do
  [[ "$oldpkg" == "$NEW" ]] && continue
  OLD_SLASH="${oldpkg//./\/}"
  while IFS= read -r srcroot; do
    [[ -z "$srcroot" ]] && continue
    olddir="$srcroot/$OLD_SLASH"
    [[ -d "$olddir" ]] || continue
    newdir="$srcroot/$NEW_SLASH"
    echo "  move $olddir -> $newdir"
    run mkdir -p "$(dirname "$newdir")"
    if [[ -d "$newdir" ]]; then
      run cp -a "$olddir/." "$newdir/" 2>/dev/null || true
      run rm -rf "$olddir"
    else
      run git mv "$olddir" "$newdir" 2>/dev/null || run mv "$olddir" "$newdir"
    fi
  done < <(find . -type d -name java -not -path '*/build/*' -not -path '*/.git/*' 2>/dev/null)
done

echo ""
echo "Done. New package: $NEW"
if [[ "$DRY" == "--dry-run" ]]; then
  echo "(dry run — no changes written)"
else
  echo "Next: git add -A && git commit -m 'rename: ...' && git push"
fi