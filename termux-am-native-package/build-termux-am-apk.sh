#!/bin/bash
# Build am.apk from termux-am-library AAR using d8.
#
# Usage:
#   ./build-termux-am-apk.sh <aar-path> <sdk-dir> <output-dir>
#
#   aar-path    : Path to termux-am-library AAR (from Gradle cache)
#   sdk-dir     : Android SDK directory (e.g. /opt/android-sdk)
#   output-dir  : Where to copy am.apk (typically assets/am-socket/)
#
# Converts classes.jar → classes.dex → am.apk for use with app_process.

set -euo pipefail

AAR_PATH="$1"
SDK_DIR="$2"
OUTPUT_DIR="$3"

if [ -z "$AAR_PATH" ] || [ -z "$SDK_DIR" ] || [ -z "$OUTPUT_DIR" ]; then
    echo "Usage: $0 <aar-path> <sdk-dir> <output-dir>" 1>&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build/am-apk-tmp"

# Resolve build-tools version (use latest available, exclude "wrappers")
BUILD_TOOLS_DIR=$(ls -d "${SDK_DIR}/build-tools/"*/ 2>/dev/null | grep -v wrappers | sort -V | tail -1)
if [ -z "$BUILD_TOOLS_DIR" ]; then
    echo "ERROR: No build-tools found in ${SDK_DIR}/build-tools/" 1>&2
    exit 1
fi

D8="${BUILD_TOOLS_DIR}/d8"
PLATFORM_JAR="${SDK_DIR}/platforms/android-34/android.jar"

if [ ! -f "$PLATFORM_JAR" ]; then
    # Fallback: try to find any android.jar
    PLATFORM_JAR=$(find "${SDK_DIR}/platforms" -name "android.jar" 2>/dev/null | head -1)
fi

if [ ! -f "$D8" ]; then
    echo "ERROR: d8 not found at $D8" 1>&2
    exit 1
fi

if [ ! -f "$PLATFORM_JAR" ]; then
    echo "ERROR: android.jar not found" 1>&2
    exit 1
fi

mkdir -p "$BUILD_DIR"

# Step 1: Extract classes.jar from AAR
echo "Extracting classes.jar from $AAR_PATH ..."
unzip -o "$AAR_PATH" classes.jar -d "$BUILD_DIR" > /dev/null 2>&1

if [ ! -f "${BUILD_DIR}/classes.jar" ]; then
    echo "ERROR: classes.jar not found in AAR" 1>&2
    exit 1
fi

# Step 2: Convert to DEX using d8
echo "Converting to DEX (d8) ..."
"$D8" \
    --lib "$PLATFORM_JAR" \
    --release \
    --output "$BUILD_DIR" \
    "${BUILD_DIR}/classes.jar" 2>&1

if [ ! -f "${BUILD_DIR}/classes.dex" ]; then
    echo "ERROR: d8 failed to produce classes.dex" 1>&2
    exit 1
fi

# Step 3: Create minimal AndroidManifest.xml
echo "Creating AndroidManifest.xml ..."
cat > "${BUILD_DIR}/AndroidManifest.xml" << 'MANIFEST'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.termux.am">
    <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="28" />
    <application android:hasCode="true" />
</manifest>
MANIFEST

# Step 4: Package DEX + manifest into am.apk
echo "Packaging am.apk ..."
mkdir -p "${BUILD_DIR}/apk"
cp "${BUILD_DIR}/classes.dex" "${BUILD_DIR}/apk/"
cp "${BUILD_DIR}/AndroidManifest.xml" "${BUILD_DIR}/apk/"

mkdir -p "$OUTPUT_DIR"
cd "${BUILD_DIR}/apk"
zip -r "${OUTPUT_DIR}/am.apk" AndroidManifest.xml classes.dex > /dev/null 2>&1

echo ""
echo "✓ am.apk built at ${OUTPUT_DIR}/am.apk"
ls -la "${OUTPUT_DIR}/am.apk"

# Cleanup temp build dir
rm -rf "${BUILD_DIR}/classes.jar" "${BUILD_DIR}/classes.dex" "${BUILD_DIR}/apk" "${BUILD_DIR}/AndroidManifest.xml"
