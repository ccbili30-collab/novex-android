#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_FILE="$SCRIPT_DIR/../../src/android/app/build.gradle.kts"

daily_block="$(sed -n '/create("daily") {/,/^        }/p' "$BUILD_FILE")"
for expected in \
  'initWith(getByName("release"))' \
  'isMinifyEnabled = false' \
  'signingConfig = if (hasReleaseSigningEnvironment)'; do
  if [[ "$daily_block" != *"$expected"* ]]; then
    echo "daily preview build type is missing: $expected" >&2
    exit 1
  fi
done

echo "android daily preview build-type tests passed"
