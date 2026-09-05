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

core_output="$($RUNNER \
  --dry-run \
  --mode auto \
  --changed-file src/android/novex-core/src/main/kotlin/com/openminis/app/novex/domain/NovexToolContract.kt)"

for expected in \
  "plan=novex-core" \
  "coverage=novex-core-tests" \
  " -p novex-core test" \
  "--daemon"; do
  if [[ "$core_output" != *"$expected"* ]]; then
    echo "missing Novex core dry-run output: $expected" >&2
    exit 1
  fi
done

if [[ "$core_output" == *":app:compilePreviewDebugKotlin"* ]] || \
   [[ "$core_output" == *":app:testPreviewDebugUnitTest"* ]] || \
   [[ "$core_output" == *"--no-daemon"* ]]; then
  echo "Novex core fast checks must not compile the Android app or disable the persistent daemon" >&2
  exit 1
fi

echo "Android Novex core fast-check runner tests passed"

runner_source="$(<"$RUNNER")"
for expected in \
  'remote_gradle_log=' \
  '> "\$remote_gradle_log" 2>&1' \
  'cat "\$remote_gradle_log"'; do
  if [[ "$runner_source" != *"$expected"* ]]; then
    echo "Windows fast checks must isolate the Gradle daemon from the SSH output pipe: $expected" >&2
    exit 1
  fi
done

echo "android Windows daemon output isolation tests passed"

for expected in \
  'if [[ "$plan" == "novex-core" ]]' \
  'novex-core' \
  'remote_state_manifest=".novex-fast-files-$plan"' \
  '< "$manifest" > "$remote_hash_manifest"'; do
  if [[ "$runner_source" != *"$expected"* ]]; then
    echo "Novex core fast checks must scan and sync only their source scope: $expected" >&2
    exit 1
  fi
done

echo "Android Novex core scoped-sync tests passed"

document_output="$($RUNNER \
  --dry-run \
  --mode auto \
  --changed-file src/android/app/src/main/java/com/openminis/app/data/attachments/NovexDocumentSnapshotExtractor.kt)"

for expected in \
  "plan=novex-document" \
  "coverage=preview-document-tests" \
  "com.openminis.app.data.attachments.*"; do
  if [[ "$document_output" != *"$expected"* ]]; then
    echo "missing Novex document dry-run output: $expected" >&2
    exit 1
  fi
done

echo "Android Novex document fast-check runner tests passed"

batched_hashers="$({ grep -o 'xargs -0 sha256sum' "$RUNNER" || true; } | wc -l | tr -d ' ')"
if [[ "$batched_hashers" -lt 2 ]]; then
  echo "Windows mirror verification must hash its manifest in one batch" >&2
  exit 1
fi

echo "android Windows batched mirror verification tests passed"

normalized_hash_paths="$({ grep -o 'path#' "$RUNNER" || true; } | wc -l | tr -d ' ')"
if [[ "$normalized_hash_paths" -lt 2 ]]; then
  echo "Windows sha256sum binary path markers must be normalized" >&2
  exit 1
fi

echo "android Windows hash path normalization tests passed"

for expected in \
  'changed_hash_manifest="$(mktemp)"' \
  '"$changed_hash_manifest"' \
  'tee '\''$remote_expected_hashes'\'' >/dev/null" < "$changed_hash_manifest"'; do
  if [[ "$runner_source" != *"$expected"* ]]; then
    echo "Post-upload verification must hash only files changed in this run: $expected" >&2
    exit 1
  fi
done

echo "android Windows changed-file verification tests passed"

package_output="$($RUNNER \
  --dry-run \
  --mode novex-ui \
  --package-preview-version 0.2.12-beta.31 \
  --changed-file src/android/app/src/main/java/com/openminis/app/ui/novex/NovexContentPage.kt)"

for expected in \
  "package_version=0.2.12-beta.31" \
  "package_tier=daily" \
  ":app:assemblePreviewDaily" \
  "--daemon" \
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
