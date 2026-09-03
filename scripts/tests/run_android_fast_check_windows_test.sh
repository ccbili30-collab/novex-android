#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNNER="$SCRIPT_DIR/../run_android_fast_check_windows.sh"

output="$($RUNNER \
  --dry-run \
  --mode auto \
  --changed-file src/android/app/src/main/java/com/openminis/app/ui/novex/NovexContentPage.kt)"

for expected in \
  "plan=novex-ui" \
  "host=win-zhz" \
  "remote_dir=/c/Users/16014/CodexBuild/novex-fast" \
  ":app:testPreviewDebugUnitTest" \
  "coverage=preview-compile,targeted-tests" \
  "com.openminis.app.ui.novex.*"; do
  if [[ "$output" != *"$expected"* ]]; then
    echo "missing dry-run output: $expected" >&2
    exit 1
  fi
done

if [[ "$output" == *":app:testStableDebugUnitTest"* ]]; then
  echo "Novex UI fast checks must not compile the stable variant" >&2
  exit 1
fi

echo "android Windows fast-check runner tests passed"
