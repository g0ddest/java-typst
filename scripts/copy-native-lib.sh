#!/usr/bin/env bash
# Copies the locally-built native library into target/classes/native/{platform}/
# so that NativeLibLoader can find it at runtime.
#
# In CI release builds, all platform binaries are pre-placed into
# src/main/resources/native/{platform}/ BEFORE Maven runs, so Maven's
# standard resource copying handles them. This script only adds the
# locally-built binary for the current platform.

set -euo pipefail

RELEASE_DIR="$1"
OUTPUT_BASE="$2"

# Detect current platform
case "$(uname -s)" in
    Linux*)  OS_NAME="linux" ;;
    Darwin*) OS_NAME="macos" ;;
    CYGWIN*|MINGW*|MSYS*) OS_NAME="windows" ;;
    *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac

case "$(uname -m)" in
    x86_64|amd64)  ARCH_NAME="x86_64" ;;
    aarch64|arm64) ARCH_NAME="aarch64" ;;
    *) echo "Unsupported arch: $(uname -m)" >&2; exit 1 ;;
esac

PLATFORM="${OS_NAME}-${ARCH_NAME}"
TARGET_DIR="${OUTPUT_BASE}/${PLATFORM}"
mkdir -p "$TARGET_DIR"

# Copy native library for current platform
COPIED=0
for ext in so dylib dll; do
    for f in "$RELEASE_DIR"/*."$ext"; do
        if [ -f "$f" ]; then
            cp "$f" "$TARGET_DIR/"
            echo "Copied $(basename "$f") -> native/${PLATFORM}/"
            COPIED=1
        fi
    done
done

if [ "$COPIED" -eq 0 ]; then
    echo "WARNING: No native library found in $RELEASE_DIR" >&2
    echo "Run 'cargo build --release' in native/ first" >&2
    exit 1
fi
