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

domain_output="$($RUNNER \
  --dry-run \
  --mode auto \
  --changed-file src/android/app/src/main/java/com/openminis/app/novex/domain/NovexConversationConfiguration.kt)"

for expected in \
  "plan=novex-domain" \
  "coverage=preview-domain-tests" \
  "com.openminis.app.novex.domain.*"; do
  if [[ "$domain_output" != *"$expected"* ]]; then
    echo "missing domain dry-run output: $expected" >&2
    exit 1
  fi
done

if [[ "$domain_output" == *":app:testStableDebugUnitTest"* ]]; then
  echo "Novex domain fast checks must not run stable tests" >&2
  exit 1
fi

echo "android Windows domain fast-check runner tests passed"

package_output="$($RUNNER \
  --dry-run \
  --mode novex-ui \
  --package-preview-version 0.2.12-beta.31 \
  --changed-file src/android/app/src/main/java/com/openminis/app/ui/novex/NovexContentPage.kt)"

for expected in \
  "package_version=0.2.12-beta.31" \
  "package_tier=daily" \
  ":app:assemblePreviewDaily" \
  "--no-daemon" \
  "-Xmx4g" \
  "kotlin.compiler.execution.strategy=in-process" \
  "expected_package=com.noven.player.preview"; do
  if [[ "$package_output" != *"$expected"* ]]; then
    echo "missing preview-package dry-run output: $expected" >&2
    exit 1
  fi
done

gradle_invocations="$(printf '%s\n' "$package_output" | grep -o './gradlew' | wc -l | tr -d ' ')"
if [[ "$gradle_invocations" != "1" ]]; then
  echo "daily preview validation and packaging must share one Gradle invocation" >&2
  exit 1
fi

if [[ "$package_output" == *":app:assembleStableRelease"* ]] || \
   [[ "$package_output" == *":app:assemblePreviewRelease"* ]]; then
  echo "Windows daily preview packaging must never run an R8 release candidate" >&2
  exit 1
fi

echo "android Windows preview-package runner tests passed"
