#!/usr/bin/env bash
# Verify and stage a signed Novex release APK.

set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 4 ]; then
    echo "Usage: $0 APK_PATH [EXPECTED_TAG] [OUTPUT_DIR] [stable|preview]" >&2
    exit 2
fi

APK_PATH="$1"
EXPECTED_TAG="${2:-}"
OUTPUT_DIR="${3:-dist}"
UPDATE_CHANNEL="${4:-stable}"

case "$UPDATE_CHANNEL" in
    stable)
        EXPECTED_PACKAGE="com.noven.player"
        VERSIONED_OUTPUT_PREFIX="novex"
        LATEST_OUTPUT_NAME="novex.apk"
        ;;
    preview)
        EXPECTED_PACKAGE="com.noven.player.preview"
        VERSIONED_OUTPUT_PREFIX="novex-preview"
        LATEST_OUTPUT_NAME="novex-preview.novex"
        VERSIONED_OUTPUT_EXTENSION="novex"
        ;;
    *)
        echo "Unknown update channel: $UPDATE_CHANNEL" >&2
        exit 2
        ;;
esac

if [ ! -s "$APK_PATH" ]; then
    echo "Release APK is missing or empty: $APK_PATH" >&2
    exit 1
fi

find_android_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
        return
    fi

    local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk_root" ] && [ -d "$sdk_root/build-tools" ]; then
        find "$sdk_root/build-tools" -type f -name "$name" | sort -V | tail -1
    fi
}

APKSIGNER="$(find_android_tool apksigner)"
AAPT="$(find_android_tool aapt)"
if [ -z "$APKSIGNER" ] || [ -z "$AAPT" ]; then
    echo "Android build tools (apksigner and aapt) are required." >&2
    exit 1
fi

SIGNATURE_DETAILS="$("$APKSIGNER" verify --verbose --print-certs "$APK_PATH")"
printf '%s\n' "$SIGNATURE_DETAILS"

if [ -n "${EXPECTED_CERT_SHA256:-}" ]; then
    ACTUAL_CERT_SHA256="$(
        printf '%s\n' "$SIGNATURE_DETAILS" |
            sed -n 's/^.*certificate SHA-256 digest: //p' |
            head -1 |
            tr -d ':[:space:]' |
            tr '[:upper:]' '[:lower:]'
    )"
    NORMALIZED_EXPECTED_CERT_SHA256="$(
        printf '%s' "$EXPECTED_CERT_SHA256" |
            tr -d ':[:space:]' |
            tr '[:upper:]' '[:lower:]'
    )"
    if [ -z "$ACTUAL_CERT_SHA256" ] || [ "$ACTUAL_CERT_SHA256" != "$NORMALIZED_EXPECTED_CERT_SHA256" ]; then
        echo "Signing certificate mismatch: actual=$ACTUAL_CERT_SHA256 expected=$NORMALIZED_EXPECTED_CERT_SHA256" >&2
        exit 1
    fi
fi

BADGING="$("$AAPT" dump badging "$APK_PATH")"
PACKAGE_LINE="$(printf '%s\n' "$BADGING" | sed -n '1p')"
PACKAGE_NAME="$(printf '%s\n' "$PACKAGE_LINE" | sed -n "s/.*package: name='\([^']*\)'.*/\1/p")"
VERSION_CODE="$(printf '%s\n' "$PACKAGE_LINE" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p")"
VERSION_NAME="$(printf '%s\n' "$PACKAGE_LINE" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")"

if [ "$PACKAGE_NAME" != "$EXPECTED_PACKAGE" ]; then
    echo "Unexpected application id: $PACKAGE_NAME (expected $EXPECTED_PACKAGE)" >&2
    exit 1
fi
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
    echo "Unable to read version metadata from $APK_PATH" >&2
    exit 1
fi
if [ -n "$EXPECTED_TAG" ] && [ "$EXPECTED_TAG" != "v$VERSION_NAME" ]; then
    echo "Tag/version mismatch: tag=$EXPECTED_TAG APK=v$VERSION_NAME" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
if [ "$UPDATE_CHANNEL" = "stable" ]; then
    VERSIONED_OUTPUT_EXTENSION="apk"
fi
cp "$APK_PATH" "$OUTPUT_DIR/$VERSIONED_OUTPUT_PREFIX-$VERSION_NAME.$VERSIONED_OUTPUT_EXTENSION"
cp "$APK_PATH" "$OUTPUT_DIR/$LATEST_OUTPUT_NAME"

if command -v sha256sum >/dev/null 2>&1; then
    (
        cd "$OUTPUT_DIR"
        find . -maxdepth 1 -type f \( -name '*.apk' -o -name '*.novex' \) -print | sed 's#^./##' | sort | xargs sha256sum > SHA256SUMS.txt
    )
else
    (
        cd "$OUTPUT_DIR"
        find . -maxdepth 1 -type f \( -name '*.apk' -o -name '*.novex' \) -print | sed 's#^./##' | sort | xargs shasum -a 256 > SHA256SUMS.txt
    )
fi

printf 'Verified channel=%s package=%s versionCode=%s versionName=%s\n' \
    "$UPDATE_CHANNEL" "$PACKAGE_NAME" "$VERSION_CODE" "$VERSION_NAME"
