#!/usr/bin/env bash
#
# Cross-compile the NobodyWho JNI bridge for Android arm64-v8a.
#
# Run from the CalculatorM3 project root:
#   ./native/build-android.sh
#
# Prerequisites (one of):
#   A) Nix: cd ../nobodywho && nix develop '.#android' --command bash -c "cd ../CalculatorM3 && ./native/build-android.sh"
#   B) Manual: set ANDROID_NDK to your NDK path (e.g. ~/Android/Sdk/ndk/28.2.13676358)
#
# Output is automatically copied to app/src/main/jniLibs/arm64-v8a/

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET="aarch64-linux-android"
API_LEVEL="${API_LEVEL:-31}"

# If not in Nix shell, try to set up toolchain from ANDROID_NDK
if [ -z "${CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER:-}" ]; then
    if [ -z "${ANDROID_NDK:-}" ]; then
        echo "Error: ANDROID_NDK not set and not in Nix android shell."
        echo ""
        echo "Option A (Nix):"
        echo "  cd ../nobodywho && nix develop '.#android' --command bash -c 'cd ../CalculatorM3 && ./native/build-android.sh'"
        echo ""
        echo "Option B (Manual):"
        echo "  export ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358"
        echo "  ./native/build-android.sh"
        exit 1
    fi

    # Detect host prebuilt dir
    HOST_TAG=""
    case "$(uname -s)" in
        Linux*)  HOST_TAG="linux-x86_64" ;;
        Darwin*) HOST_TAG="darwin-x86_64" ;;
        *)       echo "Unsupported host OS"; exit 1 ;;
    esac

    TOOLCHAIN="${ANDROID_NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
    export CC_aarch64_linux_android="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang"
    export CXX_aarch64_linux_android="${CC_aarch64_linux_android}++"
    export AR_aarch64_linux_android="${TOOLCHAIN}/bin/llvm-ar"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${CC_aarch64_linux_android}"
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="${TOOLCHAIN}/bin/llvm-ar"
fi

# Ensure the Rust target is installed
rustup target add "${TARGET}" 2>/dev/null || true

echo "Building nobodywho-android JNI bridge for ${TARGET} (API ${API_LEVEL})..."
cd "${SCRIPT_DIR}"
cargo build --target "${TARGET}" --release

SO_PATH="target/${TARGET}/release/libnobodywho_android.so"
if [ -f "${SO_PATH}" ]; then
    DEST="${PROJECT_DIR}/app/src/main/jniLibs/arm64-v8a"
    mkdir -p "${DEST}"
    cp "${SO_PATH}" "${DEST}/libnobodywho_android.so"
    echo ""
    echo "Build successful!"
    echo "Installed to: ${DEST}/libnobodywho_android.so"
else
    echo "Error: Expected output not found at ${SO_PATH}"
    exit 1
fi
