#!/usr/bin/env python3
"""
Migrate all .in scripts to use shared termux-api-lib.sh.
v2 — fixes script detection for scripts with custom preprocessing.
"""

import os
import re
import sys

SCRIPTS_DIR = os.path.join(os.path.dirname(__file__), "scripts")
LIB_SOURCE = ". \"@TERMUX_PREFIX@/bin/termux-api-lib.sh\""
PREFIX = "@TERMUX_PREFIX@"

METHOD_OVERRIDES = {
    "termux-api.in": "ApiEntry",
    "termux-bluetooth.in": "Bluetooth",
    "termux-wifi.in": "Wifi",
}

CUSTOM_LOGIC = {}  # scripts that need manual handling

def extract_method(content):
    for line in reversed(content.splitlines()):
        m = re.search(rf'{PREFIX}/bin/termux-api\s+(\S+)', line)
        if m:
            return m.group(1)
        m = re.search(r'am\s+broadcast.*-a\s+(\S+)', line)
        if m:
            return m.group(1)
    return None

def extract_description(content):
    """Extract the NAME line from comment header."""
    lines = content.splitlines()
    for i, line in enumerate(lines):
        stripped = line.strip()
        # Skip shebang, blank lines
        if stripped.startswith("#!") or not stripped:
            continue
        if stripped.startswith("# NAME") and i + 1 < len(lines):
            next_line = lines[i + 1].strip()
            if next_line.startswith("# "):
                return next_line[2:].strip()
        if stripped.startswith("# ") and not stripped[2:].startswith(("NAME", "SYNOPSIS", "DESCRIPTION", "OPTIONS", "EXAMPLES")):
            title = stripped[2:].strip()
            # Skip lines that start with the script command
            if "termux-api" in title or "am " in title:
                continue
            return title
    return ""

def extract_body(content):
    """Extract any custom preprocessing between shebang and final API call."""
    lines = content.splitlines()
    api_call_line = None
    body_lines = []
    in_header = True
    
    for i, line in enumerate(lines):
        if in_header:
            if line.startswith("#!") or line.startswith("# ") or line.startswith("#") or line.strip() == "":
                continue
            in_header = False
        
        if PREFIX + "/bin/termux-api" in line or "am broadcast" in line:
            api_call_line = i
            continue
        
        if api_call_line is not None:
            continue  # skip everything after API call
        
        body_lines.append(line)
    
    # Remove standard boilerplate lines
    clean_body = [l for l in body_lines if l.strip() and 
                  l.strip() not in ('set -e -u', 'set -e -u', '#') and
                  not l.strip().startswith('#!')]
    
    return clean_body

def generate_new_v2(orig_content, method):
    """Generate new content, preserving custom preprocessing."""
    if method is None:
        return None
    
    desc = extract_description(orig_content)
    if not desc or desc.startswith("termux-api "):
        # Fallback: use method name
        desc = f"{method} API"
    
    body = extract_body(orig_content)
    
    lines = []
    lines.append('#!@TERMUX_PREFIX@/bin/sh')
    lines.append('#')
    lines.append(f'# {desc}')
    lines.append('#')
    lines.append('set -e -u')
    lines.append('')
    lines.append('SCRIPTNAME="${0##*/}"')
    lines.append(f'. "@TERMUX_PREFIX@/bin/termux-api-lib.sh"')
    lines.append('')
    lines.append('parse_args "$@"')
    lines.append('set -- "${ARGS[@]}"')
    lines.append('')
    
    # Help
    desc_esc = desc.replace("'", "'\\''")
    lines.append(f'[ "$help" = 1 ] && show_help \\')
    lines.append(f"    '{desc_esc}' \\")
    lines.append(f"    '' \\")
    lines.append(f"    '  $SCRIPTNAME' \\")
    lines.append(f"    'JSON output.' \\")
    lines.append(f"    'See Termux:API app settings for permissions.'")
    lines.append('')
    
    # Custom preprocessing
    if body:
        lines.append('# ─── Input preprocessing ────────────────────────────')
        for bl in body:
            lines.append(bl)
        lines.append('')
    
    lines.append('# ─── Call API ───────────────────────────────────────────')
    lines.append(f'call_api "{method}"')
    
    return "\n".join(lines) + "\n"

def main():
    dry_run = "--dry-run" in sys.argv
    
    files = sorted(f for f in os.listdir(SCRIPTS_DIR)
                   if f.endswith(".in") and f != "termux-api-lib.sh"
                   and os.path.isfile(os.path.join(SCRIPTS_DIR, f)))
    
    updated = 0
    skipped = 0
    errors = 0
    
    for fname in files:
        fpath = os.path.join(SCRIPTS_DIR, fname)
        with open(fpath) as f:
            orig = f.read()
        
        if LIB_SOURCE in orig:
            print(f"  SKIP: {fname}")
            skipped += 1
            continue
        
        method = METHOD_OVERRIDES.get(fname) or extract_method(orig)
        if method is None:
            print(f"  ERROR: {fname} (no method)")
            errors += 1
            continue
        
        new_content = generate_new_v2(orig, method)
        if new_content is None:
            print(f"  ERROR: {fname} (generation failed)")
            errors += 1
            continue
        
        if dry_run:
            print(f"  WOULD UPDATE: {fname}")
        else:
            with open(fpath, 'w') as f:
                f.write(new_content)
            print(f"  UPDATED: {fname}")
        updated += 1
    
    print(f"\nTotal: {len(files)} | Updated: {updated} | Skipped: {skipped} | Errors: {errors}")
    if dry_run:
        print("(dry run)")

if __name__ == "__main__":
    main()
