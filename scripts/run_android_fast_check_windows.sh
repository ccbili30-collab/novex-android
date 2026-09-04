#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_ROOT="$REPO_ROOT/src/android"
PLANNER="$SCRIPT_DIR/android_fast_check_plan.sh"

host="${NOVEX_WINDOWS_HOST:-win-zhz}"
remote_dir="${NOVEX_WINDOWS_BUILD_DIR:-/c/Users/16014/CodexBuild/novex-fast}"
remote_parent="${remote_dir%/*}"
seed_dir="${NOVEX_WINDOWS_SEED_DIR:-/c/Users/16014/CodexBuild/novex-preview-home}"
mode="auto"
dry_run=false
declare -a changed_files=()

usage() {
  echo "Usage: $0 [--mode auto|novex-domain|novex-ui|full|skip] [--changed-file PATH] [--dry-run]"
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
  --console=plain
  --stacktrace
  --max-workers=4
)
declare -a first_gradle_args=()
declare -a second_gradle_args=()

case "$plan" in
  skip)
    ;;
  full)
    coverage="stable-tests,preview-compile"
    first_gradle_args=(
      "${common_gradle_args[@]}"
      :app:testStableDebugUnitTest
    )
    second_gradle_args=(
      "${common_gradle_args[@]}"
      :app:compilePreviewDebugKotlin
    )
    ;;
  novex-ui)
    coverage="preview-compile,targeted-tests"
    first_gradle_args=(
      "${common_gradle_args[@]}"
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
    first_gradle_args=(
      "${common_gradle_args[@]}"
      :app:testPreviewDebugUnitTest
      --tests 'com.openminis.app.novex.domain.*'
    )
    ;;
  *)
    echo "planner returned unsupported plan: $plan" >&2
    exit 2
    ;;
esac

printf 'plan=%s\n' "$plan"
printf 'host=%s\n' "$host"
printf 'remote_dir=%s\n' "$remote_dir"
printf 'changed_files=%s\n' "${#changed_files[@]}"
printf 'coverage=%s\n' "$coverage"
if [[ "$plan" != "skip" ]]; then
  printf 'gradle_1='
  printf ' %s' ./gradlew "${first_gradle_args[@]}"
  printf '\n'
  if [[ "${#second_gradle_args[@]}" -gt 0 ]]; then
    printf 'gradle_2='
    printf ' %s' ./gradlew "${second_gradle_args[@]}"
    printf '\n'
  fi
fi

if $dry_run || [[ "$plan" == "skip" ]]; then
  exit 0
fi

manifest="$(mktemp)"
hash_manifest="$(mktemp)"
changed_manifest="$(mktemp)"
archive="$(mktemp)"
shared_archive="$(mktemp)"
cleanup() {
  rm -f "$manifest" "$hash_manifest" "$changed_manifest" "$archive" "$shared_archive"
}
trap cleanup EXIT

report_dir="$REPO_ROOT/tmp/novex-fast-check"
state_manifest="$report_dir/windows-source-manifest.sha256"
mkdir -p "$report_dir"

(
  cd "$ANDROID_ROOT"
  {
    git ls-files -- .
    git ls-files --others --exclude-standard -- .
  } | LC_ALL=C sort -u > "$manifest"

  tr '\n' '\0' < "$manifest" | xargs -0 shasum -a 256 -- | while read -r hash path; do
    printf '%s\t%s\n' "$path" "$hash"
  done | LC_ALL=C sort > "$hash_manifest"

  if [[ -f "$state_manifest" ]]; then
    awk -F '\t' '
      NR == FNR { previous[$1] = $2; next }
      !($1 in previous) || previous[$1] != $2 { print $1 }
    ' "$state_manifest" "$hash_manifest" > "$changed_manifest"
  else
    cut -f1 "$hash_manifest" > "$changed_manifest"
  fi

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
report_path="$report_dir/$run_id-$plan.log"

ssh "$host" "mkdir -p '$remote_dir' && tee '$remote_archive' >/dev/null" < "$archive"
ssh "$host" "tee '$remote_shared_archive' >/dev/null" < "$shared_archive"
ssh "$host" "tee '$remote_manifest' >/dev/null" < "$manifest"

quoted_first_gradle=""
quoted_second_gradle=""
printf -v quoted_first_gradle '%q ' ./gradlew "${first_gradle_args[@]}"
if [[ "${#second_gradle_args[@]}" -gt 0 ]]; then
  printf -v quoted_second_gradle '%q ' ./gradlew "${second_gradle_args[@]}"
fi

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
    rm -rf .novex-fast-check.lock '$remote_archive' '$remote_shared_archive' '$remote_manifest'
  }
  trap cleanup_fast_check EXIT
  if [[ ! -f local.properties && -f '$seed_dir/local.properties' ]]; then
    cp '$seed_dir/local.properties' local.properties
  fi
  if [[ -f .novex-fast-files ]]; then
    LC_ALL=C comm -23 .novex-fast-files '$remote_manifest' | while IFS= read -r stale; do
      [[ -n \"\$stale\" ]] && rm -f -- \"\$stale\"
    done
  fi
  tar -xf '$remote_archive'
  mkdir -p '$remote_parent'
  tar -xf '$remote_shared_archive' -C '$remote_parent'
  mv '$remote_manifest' .novex-fast-files
  rm -f '$remote_archive' '$remote_shared_archive'
  export NOVEX_PREFER_CANONICAL_REPOSITORIES=true
  $quoted_first_gradle
  $quoted_second_gradle
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
