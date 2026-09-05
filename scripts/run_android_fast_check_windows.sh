#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_ROOT="$REPO_ROOT/src/android"
PLANNER="$SCRIPT_DIR/android_fast_check_plan.sh"
SYNC_PLANNER="$SCRIPT_DIR/android_source_sync_plan.sh"

host="${NOVEX_WINDOWS_HOST:-win-zhz}"
remote_dir="${NOVEX_WINDOWS_BUILD_DIR:-/c/Users/16014/CodexBuild/novex-fast}"
remote_parent="${remote_dir%/*}"
seed_dir="${NOVEX_WINDOWS_SEED_DIR:-/c/Users/16014/CodexBuild/novex-preview-home}"
mode="auto"
dry_run=false
package_preview_version=""
declare -a changed_files=()

usage() {
  echo "Usage: $0 [--mode auto|novex-core|novex-document|novex-domain|novex-ui|full|skip] [--changed-file PATH] [--package-preview-version VERSION] [--dry-run]"
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --mode)
      mode="${2:?missing value for --mode}"
      shift 2
      ;;
    --changed-file)
      changed_files+=("${2:?missing value for --changed-file}")
      shift 2
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    --package-preview-version)
      package_preview_version="${2:?missing value for --package-preview-version}"
      shift 2
      ;;
    --help | -h)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -n "$package_preview_version" ]] && \
   ! [[ "$package_preview_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+-beta\.[1-9][0-9]*$ ]]; then
  echo "preview package version must look like 0.2.12-beta.31" >&2
  exit 2
fi

if [[ "${#changed_files[@]}" -eq 0 ]]; then
  while IFS= read -r path; do
    [[ -n "$path" ]] && changed_files+=("$path")
  done < <(
    cd "$REPO_ROOT"
    {
      git diff --name-only HEAD -- src/android scripts .github/workflows
      git ls-files --others --exclude-standard -- src/android scripts .github/workflows
    } | LC_ALL=C sort -u
  )
fi

if [[ "${#changed_files[@]}" -eq 0 ]] && git -C "$REPO_ROOT" rev-parse HEAD^ >/dev/null 2>&1; then
  while IFS= read -r path; do
    [[ -n "$path" ]] && changed_files+=("$path")
  done < <(git -C "$REPO_ROOT" diff --name-only HEAD^ HEAD -- src/android scripts .github/workflows)
fi

plan="$($PLANNER "$mode" "${changed_files[@]}")"
coverage="none"

declare -a common_gradle_args=(
  --daemon
  --console=plain
  --stacktrace
  --max-workers=4
  -Dorg.gradle.daemon.idletimeout=3600000
  "-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8"
  -Pkotlin.compiler.execution.strategy=in-process
)
declare -a validation_gradle_args=()

case "$plan" in
  skip)
    ;;
  full)
    coverage="stable-tests,preview-compile"
    validation_gradle_args=(
      :app:testStableDebugUnitTest
      :app:compilePreviewDebugKotlin
    )
    ;;
  novex-ui)
    coverage="preview-compile,targeted-tests"
    validation_gradle_args=(
      :app:testPreviewDebugUnitTest
      --tests 'com.openminis.app.ui.novex.*'
      --tests 'com.openminis.app.ui.sessions.Novex*'
      --tests 'com.openminis.app.ui.navigation.Novex*'
      --tests 'com.openminis.app.ui.settings.World*'
      --tests 'com.openminis.app.ui.settings.Character*'
      --tests 'com.openminis.app.ui.settings.NovexNativeCard*'
    )
    ;;
  novex-domain)
    coverage="preview-domain-tests"
    validation_gradle_args=(
      :app:testPreviewDebugUnitTest
      --tests 'com.openminis.app.novex.domain.*'
    )
    ;;
  novex-core)
    coverage="novex-core-tests"
    validation_gradle_args=(
      -p novex-core
      test
    )
    ;;
  novex-document)
    coverage="preview-document-tests"
    validation_gradle_args=(
      :app:testPreviewDebugUnitTest
      --tests 'com.openminis.app.data.attachments.*'
      --tests 'com.openminis.app.tools.NovexDocumentAgentToolsTest'
      --tests 'com.openminis.app.ui.chat.AttachmentPromptMetadataTest'
      --tests 'com.openminis.app.ui.chat.DocxAttachmentRequestChainTest'
      --tests 'com.openminis.app.data.character.CharacterPromptComposerTest'
    )
    ;;
  *)
    echo "planner returned unsupported plan: $plan" >&2
    exit 2
    ;;
esac

declare -a gradle_args=("${common_gradle_args[@]}" "${validation_gradle_args[@]}")
if [[ -n "$package_preview_version" ]]; then
  gradle_args+=(:app:assemblePreviewDaily)
fi

printf 'plan=%s\n' "$plan"
printf 'host=%s\n' "$host"
printf 'remote_dir=%s\n' "$remote_dir"
printf 'changed_files=%s\n' "${#changed_files[@]}"
printf 'coverage=%s\n' "$coverage"
if [[ -n "$package_preview_version" ]]; then
  printf 'package_version=%s\n' "$package_preview_version"
  printf 'package_tier=daily\n'
  printf 'expected_package=com.noven.player.preview\n'
fi
if [[ "${#validation_gradle_args[@]}" -gt 0 || -n "$package_preview_version" ]]; then
  printf 'gradle='
  printf ' %s' ./gradlew "${gradle_args[@]}"
  printf '\n'
fi

if $dry_run || { [[ "$plan" == "skip" ]] && [[ -z "$package_preview_version" ]]; }; then
  exit 0
fi

manifest="$(mktemp)"
hash_manifest="$(mktemp)"
remote_hash_manifest="$(mktemp)"
changed_manifest="$(mktemp)"
changed_hash_manifest="$(mktemp)"
archive="$(mktemp)"
shared_archive="$(mktemp)"
cleanup() {
  rm -f "$manifest" "$hash_manifest" "$remote_hash_manifest" "$changed_manifest" "$changed_hash_manifest" "$archive" "$shared_archive"
}
trap cleanup EXIT

report_dir="$REPO_ROOT/tmp/novex-fast-check"
state_manifest="$report_dir/windows-source-manifest.sha256"
mkdir -p "$report_dir"

(
  cd "$ANDROID_ROOT"
  if [[ "$plan" == "novex-core" ]]; then
    {
      git ls-files -- gradlew gradlew.bat gradle novex-core
      git ls-files --others --exclude-standard -- gradlew gradlew.bat gradle novex-core
    } | LC_ALL=C sort -u > "$manifest"
  else
    {
      git ls-files -- .
      git ls-files --others --exclude-standard -- .
    } | LC_ALL=C sort -u > "$manifest"
  fi

  tr '\n' '\0' < "$manifest" | xargs -0 shasum -a 256 -- | while read -r hash path; do
    printf '%s\t%s\n' "$path" "$hash"
  done | LC_ALL=C sort > "$hash_manifest"

)

# Compare against what is actually present in the Windows mirror. A failed
# build is allowed to leave source files there, so a last-successful local
# manifest is not proof that the remote contents still match.
ssh "$host" "
  set -euo pipefail
  cd '$remote_dir' 2>/dev/null || exit 0
  while IFS= read -r path; do
    if [[ -f \"\$path\" ]]; then
      printf '%s\\0' \"\$path\"
    fi
  done | xargs -0 sha256sum -- | while read -r hash path; do
    path=\${path#\\*}
    printf '%s\\t%s\\n' \"\$path\" \"\$hash\"
  done
" < "$manifest" > "$remote_hash_manifest"

"$SYNC_PLANNER" "$hash_manifest" "$remote_hash_manifest" > "$changed_manifest"
awk -F '\t' '
  NR == FNR { changed[$0] = true; next }
  $1 in changed { print }
' "$changed_manifest" "$hash_manifest" > "$changed_hash_manifest"
(
  cd "$ANDROID_ROOT"
  COPYFILE_DISABLE=1 tar --no-xattrs -cf "$archive" -T "$changed_manifest"
)

# Android tests and packaging consume the shared shell-compatibility tables from
# ../shared. Keep that small cross-platform dependency beside the fast mirror.
COPYFILE_DISABLE=1 tar --no-xattrs -C "$REPO_ROOT/src" -cf "$shared_archive" shared/bashism

sync_files="$(wc -l < "$changed_manifest" | tr -d ' ')"
printf 'sync_files=%s\n' "$sync_files"

source_sha="$(git -C "$REPO_ROOT" rev-parse --short=12 HEAD)"
if ! git -C "$REPO_ROOT" diff --quiet -- src/android || \
   [[ -n "$(git -C "$REPO_ROOT" ls-files --others --exclude-standard -- src/android)" ]]; then
  source_sha="${source_sha}-dirty"
fi
run_id="${source_sha}-$(date -u +%Y%m%dT%H%M%SZ)-$$"
remote_archive="$remote_dir/.novex-upload-$run_id.tar"
remote_shared_archive="$remote_dir/.novex-shared-$run_id.tar"
remote_manifest="$remote_dir/.novex-files-$run_id"
remote_expected_hashes="$remote_dir/.novex-hashes-$run_id"
remote_actual_hashes="$remote_dir/.novex-actual-hashes-$run_id"
remote_gradle_log="$remote_dir/.novex-gradle-$run_id.log"
remote_state_manifest=".novex-fast-files-$plan"
report_path="$report_dir/$run_id-$plan.log"

ssh "$host" "mkdir -p '$remote_dir' && tee '$remote_archive' >/dev/null" < "$archive"
ssh "$host" "tee '$remote_shared_archive' >/dev/null" < "$shared_archive"
ssh "$host" "tee '$remote_manifest' >/dev/null" < "$manifest"
ssh "$host" "tee '$remote_expected_hashes' >/dev/null" < "$changed_hash_manifest"

quoted_gradle=""
printf -v quoted_gradle '%q ' ./gradlew "${gradle_args[@]}"

start_epoch="$(date +%s)"
set +e
ssh "$host" "
  set -euo pipefail
  cd '$remote_dir'
  if ! mkdir .novex-fast-check.lock 2>/dev/null; then
    echo 'another Windows fast check is already running' >&2
    exit 75
  fi
  cleanup_fast_check() {
    rm -rf .novex-fast-check.lock '$remote_archive' '$remote_shared_archive' '$remote_manifest' '$remote_expected_hashes' '$remote_actual_hashes' '$remote_gradle_log'
  }
  trap cleanup_fast_check EXIT
  if [[ ! -f local.properties && -f '$seed_dir/local.properties' ]]; then
    cp '$seed_dir/local.properties' local.properties
  fi
  if [[ -f '$remote_state_manifest' ]]; then
    LC_ALL=C comm -23 '$remote_state_manifest' '$remote_manifest' | while IFS= read -r stale; do
      [[ -n \"\$stale\" ]] && rm -f -- \"\$stale\"
    done
  fi
  tar -xf '$remote_archive'
  mkdir -p '$remote_parent'
  tar -xf '$remote_shared_archive' -C '$remote_parent'
  mv '$remote_manifest' '$remote_state_manifest'
  rm -f '$remote_archive' '$remote_shared_archive'
  : > '$remote_actual_hashes'
  if [[ -s '$remote_expected_hashes' ]]; then
    cut -f 1 '$remote_expected_hashes' | while IFS= read -r path; do
      [[ -f \"\$path\" ]] && printf '%s\\0' \"\$path\"
    done | xargs -0 sha256sum -- | while read -r hash path; do
      path=\${path#\\*}
      printf '%s\\t%s\\n' \"\$path\" \"\$hash\"
    done > '$remote_actual_hashes'
  fi
  if ! cmp -s '$remote_expected_hashes' '$remote_actual_hashes'; then
    mismatch=\$(awk -F '\\t' '
      NR == FNR { actual[\$1] = \$2; next }
      !(\$1 in actual) { print \"missing: \" \$1; exit }
      actual[\$1] != \$2 { print \"mismatch: \" \$1; exit }
    ' '$remote_actual_hashes' '$remote_expected_hashes')
    echo \"Windows mirror source verification failed: \$mismatch\" >&2
    exit 1
  fi
  rm -f '$remote_expected_hashes' '$remote_actual_hashes'
  export NOVEX_PREFER_CANONICAL_REPOSITORIES=true
  if [[ -n '$package_preview_version' ]]; then
    export NOVEX_VERSION_NAME='$package_preview_version'
  fi
  # On Windows Git Bash, a newly started Gradle daemon can inherit the SSH
  # stdout pipe and keep an otherwise completed fast check open. Isolate the
  # build output in a remote file, then replay it after the Gradle client exits.
  remote_gradle_log='$remote_gradle_log'
  set +e
  $quoted_gradle > "\$remote_gradle_log" 2>&1
  gradle_status=\$?
  set -e
  cat "\$remote_gradle_log"
  [[ "\$gradle_status" -eq 0 ]] || exit "\$gradle_status"
  if [[ -n '$package_preview_version' ]]; then
    apk='app/build/outputs/apk/preview/daily/app-preview-daily.apk'
    sdk_windows=\$(sed -n 's/^sdk.dir=//p' local.properties | head -1)
    sdk=\$(cygpath -u \"\$sdk_windows\")
    apksigner=\$(find \"\$sdk/build-tools\" -type f -name apksigner.bat | sort -V | tail -1)
    aapt=\$(find \"\$sdk/build-tools\" -type f -name aapt.exe | sort -V | tail -1)
    signature=\$(\"\$apksigner\" verify --verbose --print-certs \"\$apk\")
    cert=\$(printf '%s\\n' \"\$signature\" | sed -n 's/^.*certificate SHA-256 digest: //p' | head -1 | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]')
    expected_cert='cab4226b416183671281253b5f4000a28885cfa4f48146c0e7798d137e41c6a6'
    [[ \"\$cert\" == \"\$expected_cert\" ]] || { echo \"preview signing certificate mismatch\" >&2; exit 1; }
    package_line=\$(\"\$aapt\" dump badging \"\$apk\" | sed -n '1p')
    package_name=\$(printf '%s\\n' \"\$package_line\" | sed -n \"s/.*package: name='\\([^']*\\)'.*/\\1/p\")
    version_name=\$(printf '%s\\n' \"\$package_line\" | sed -n \"s/.*versionName='\\([^']*\\)'.*/\\1/p\")
    [[ \"\$package_name\" == 'com.noven.player.preview' ]] || { echo \"preview package id mismatch\" >&2; exit 1; }
    [[ \"\$version_name\" == '$package_preview_version' ]] || { echo \"preview package version mismatch\" >&2; exit 1; }
    printf 'Verified channel=preview package=%s versionName=%s\\n' \"\$package_name\" \"\$version_name\"
    printf 'candidate_apk=%s/%s\\n' '$remote_dir' \"\$apk\"
    printf 'candidate_sha256=%s\\n' \"\$(sha256sum \"\$apk\" | cut -d ' ' -f 1)\"
    printf 'candidate_cert_sha256=%s\\n' \"\$cert\"
  fi
" 2>&1 | tee "$report_path"
status="${PIPESTATUS[0]}"
set -e
elapsed="$(( $(date +%s) - start_epoch ))"

if [[ "$status" -eq 0 ]]; then
  cp "$hash_manifest" "$state_manifest"
fi

{
  printf '\nresult=%s\n' "$([[ "$status" -eq 0 ]] && echo passed || echo failed)"
  printf 'source=%s\n' "$source_sha"
  printf 'plan=%s\n' "$plan"
  printf 'elapsed_seconds=%s\n' "$elapsed"
  printf 'report=%s\n' "$report_path"
} | tee -a "$report_path"

exit "$status"
