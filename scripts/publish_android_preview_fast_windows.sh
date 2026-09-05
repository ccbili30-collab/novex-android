#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
FAST_CHECK_RUNNER="${NOVEX_FAST_CHECK_RUNNER:-$SCRIPT_DIR/run_android_fast_check_windows.sh}"
SSH_BIN="${NOVEX_SSH_BIN:-ssh}"
GH_BIN="${NOVEX_GH_BIN:-gh}"
WINDOWS_HOST="${NOVEX_WINDOWS_HOST:-win-zhz}"
OUTPUT_ROOT="${NOVEX_FAST_PUBLISH_OUTPUT_DIR:-$REPO_ROOT/tmp/novex-fast-publish}"
EXPECTED_CERT_SHA256="cab4226b416183671281253b5f4000a28885cfa4f48146c0e7798d137e41c6a6"

dry_run=false
publish=false
skip_fetch=false

usage() {
  echo "Usage: $0 [--dry-run | --publish] [--skip-fetch]"
}

while [[ "$#" -gt 0 ]]; do
  case "$1" in
    --dry-run)
      dry_run=true
      ;;
    --publish)
      publish=true
      ;;
    --skip-fetch)
      skip_fetch=true
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
  shift
done

if $dry_run && $publish; then
  echo "--dry-run and --publish are mutually exclusive" >&2
  exit 2
fi
if $publish && $skip_fetch; then
  echo "--publish cannot skip the remote freshness check" >&2
  exit 2
fi

branch="${NOVEX_FAST_PUBLISH_BRANCH-$(git -C "$REPO_ROOT" branch --show-current)}"
if [[ "$branch" != "next" ]]; then
  echo "fast preview publishing is restricted to the next branch" >&2
  exit 1
fi

status="${NOVEX_FAST_PUBLISH_STATUS-$(
  git -C "$REPO_ROOT" status --porcelain --untracked-files=normal -- \
    src/android \
    scripts \
    .github/workflows/android-preview.yml \
    .github/release-notes
)}"
if [[ -n "$status" ]]; then
  echo "uncommitted release input must be committed before preview publishing" >&2
  printf '%s\n' "$status" >&2
  exit 1
fi

if ! $skip_fetch && [[ -z "${NOVEX_FAST_PUBLISH_REMOTE_SHA:-}" ]]; then
  git -C "$REPO_ROOT" fetch --quiet origin next --tags
fi

source_sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"
if $publish; then
  remote_sha="${NOVEX_FAST_PUBLISH_REMOTE_SHA-$(git -C "$REPO_ROOT" rev-parse refs/remotes/origin/next)}"
  if [[ "$source_sha" != "$remote_sha" ]]; then
    echo "next must be pushed without divergence before preview publishing" >&2
    exit 1
  fi
fi

stable_version="$(
  sed -n 's/.*?: "\([0-9][0-9.]*\)"/\1/p' \
    "$REPO_ROOT/src/android/app/build.gradle.kts" | head -1
)"
if ! [[ "$stable_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "unable to read the preview version base" >&2
  exit 1
fi

release_tags="${NOVEX_PREVIEW_RELEASE_TAGS-$(
  "$GH_BIN" release list --limit 100 --json tagName,isPrerelease \
    --jq '.[] | select(.isPrerelease) | .tagName'
)}"
highest_beta=0
while IFS= read -r tag; do
  if [[ "$tag" =~ ^v${stable_version//./\.}-beta\.([0-9]+)$ ]]; then
    number="${BASH_REMATCH[1]}"
    if (( number > highest_beta )); then
      highest_beta="$number"
    fi
  fi
done <<< "$release_tags"

next_beta="$((highest_beta + 1))"
version="$stable_version-beta.$next_beta"
tag="v$version"

printf 'channel=preview\n'
printf 'tier=daily\n'
printf 'branch=%s\n' "$branch"
printf 'source=%s\n' "$source_sha"
printf 'version=%s\n' "$version"
printf 'tag=%s\n' "$tag"
printf 'package=com.noven.player.preview\n'
printf 'asset=novex-preview.novex\n'
printf 'host=%s\n' "$WINDOWS_HOST"
printf 'publish=%s\n' "$publish"

if $dry_run; then
  exit 0
fi

previous_tag=""
if (( highest_beta > 0 )); then
  previous_tag="v$stable_version-beta.$highest_beta"
fi

if [[ -n "${NOVEX_FAST_PUBLISH_CHANGED_FILES+x}" ]]; then
  changed_files="$NOVEX_FAST_PUBLISH_CHANGED_FILES"
elif [[ -n "$previous_tag" ]]; then
  if git -C "$REPO_ROOT" rev-parse "$previous_tag^{commit}" >/dev/null 2>&1; then
    previous_source="$previous_tag"
  else
    previous_source="$("$GH_BIN" release view "$previous_tag" --json targetCommitish --jq '.targetCommitish')"
  fi
  if ! git -C "$REPO_ROOT" cat-file -e "$previous_source^{commit}" 2>/dev/null; then
    echo "unable to resolve the previous published preview source" >&2
    exit 1
  fi
  changed_files="$(git -C "$REPO_ROOT" diff --name-only "$previous_source..$source_sha" -- src/android scripts .github/workflows)"
else
  changed_files="src/android/app/build.gradle.kts"
fi
if [[ -z "$changed_files" ]]; then
  echo "there are no unpublished Android or release-pipeline changes" >&2
  exit 1
fi

declare -a runner_args=(
  --mode auto
  --package-preview-version "$version"
)
while IFS= read -r path; do
  [[ -n "$path" ]] && runner_args+=(--changed-file "$path")
done <<< "$changed_files"

candidate_dir="$OUTPUT_ROOT/$tag"
mkdir -p "$candidate_dir"
build_log="$candidate_dir/windows-build.log"
rm -f \
  "$candidate_dir/novex-preview.novex" \
  "$candidate_dir/zz-novex-preview-installer.apk" \
  "$candidate_dir/SHA256SUMS.txt" \
  "$candidate_dir/release-notes.md" \
  "$candidate_dir/app-preview-release.apk"

set +e
"$FAST_CHECK_RUNNER" "${runner_args[@]}" 2>&1 | tee "$build_log"
runner_status="${PIPESTATUS[0]}"
set -e
if [[ "$runner_status" -ne 0 ]]; then
  echo "Windows preview validation or packaging failed" >&2
  exit "$runner_status"
fi

candidate_apk="$(sed -n 's/^candidate_apk=//p' "$build_log" | tail -1)"
reported_sha="$(sed -n 's/^candidate_sha256=//p' "$build_log" | tail -1)"
reported_cert="$(sed -n 's/^candidate_cert_sha256=//p' "$build_log" | tail -1)"
verified_line="$(sed -n 's/^Verified channel=preview /Verified channel=preview /p' "$build_log" | tail -1)"
if [[ -z "$candidate_apk" || -z "$reported_sha" || -z "$reported_cert" ]]; then
  echo "Windows preview build did not return verified candidate metadata" >&2
  exit 1
fi
if [[ "$reported_cert" != "$EXPECTED_CERT_SHA256" ]]; then
  echo "Windows preview candidate uses an unexpected signing certificate" >&2
  exit 1
fi
if [[ "$verified_line" != *"package=com.noven.player.preview"* ]] || \
   [[ "$verified_line" != *"versionName=$version"* ]]; then
  echo "Windows preview candidate metadata does not match the release plan" >&2
  exit 1
fi

downloaded_apk="$candidate_dir/app-preview-release.apk"
"$SSH_BIN" "$WINDOWS_HOST" "cat '$candidate_apk'" > "$downloaded_apk"
actual_sha="$(shasum -a 256 "$downloaded_apk" | awk '{print $1}')"
if [[ "$actual_sha" != "$reported_sha" ]]; then
  echo "downloaded preview candidate hash does not match the verified Windows artifact" >&2
  exit 1
fi

cp "$downloaded_apk" "$candidate_dir/novex-preview.novex"
cp "$downloaded_apk" "$candidate_dir/zz-novex-preview-installer.apk"
(
  cd "$candidate_dir"
  shasum -a 256 novex-preview.novex zz-novex-preview-installer.apk > SHA256SUMS.txt
)
"$SCRIPT_DIR/render_android_release_notes.sh" \
  "$stable_version" "$source_sha" preview > "$candidate_dir/release-notes.md"

printf 'candidate_dir=%s\n' "$candidate_dir"
printf 'candidate_sha256=%s\n' "$actual_sha"

if ! $publish; then
  printf 'published=false\n'
  exit 0
fi

existing_release=""
set +e
existing_release="$("$GH_BIN" release view "$tag" --json targetCommitish,isDraft,isPrerelease,assets,url 2>/dev/null)"
view_status=$?
set -e
if [[ "$view_status" -eq 0 ]]; then
  existing_target="$(printf '%s' "$existing_release" | jq -r '.targetCommitish')"
  existing_draft="$(printf '%s' "$existing_release" | jq -r '.isDraft')"
  if [[ "$existing_target" != "$source_sha" ]]; then
    echo "the planned preview tag already points at another commit" >&2
    exit 1
  fi
  if [[ "$existing_draft" != "true" ]]; then
    echo "the planned preview release is already public and will not be overwritten" >&2
    exit 1
  fi
  "$GH_BIN" release upload "$tag" \
    "$candidate_dir/novex-preview.novex" \
    "$candidate_dir/zz-novex-preview-installer.apk" \
    "$candidate_dir/SHA256SUMS.txt" \
    --clobber
else
  "$GH_BIN" release create "$tag" \
    "$candidate_dir/novex-preview.novex" \
    "$candidate_dir/zz-novex-preview-installer.apk" \
    "$candidate_dir/SHA256SUMS.txt" \
    --target "$source_sha" \
    --title "Novex $version" \
    --notes-file "$candidate_dir/release-notes.md" \
    --prerelease \
    --draft
fi

draft_release="$("$GH_BIN" release view "$tag" --json targetCommitish,isDraft,isPrerelease,assets,url)"
if [[ "$(printf '%s' "$draft_release" | jq -r '.targetCommitish')" != "$source_sha" ]] || \
   [[ "$(printf '%s' "$draft_release" | jq -r '.isDraft')" != "true" ]]; then
  echo "preview release draft does not match the verified source" >&2
  exit 1
fi

verify_uploaded_asset() {
  local name="$1"
  local local_path="$2"
  local expected_digest expected_size remote_digest remote_size
  expected_digest="sha256:$(shasum -a 256 "$local_path" | awk '{print $1}')"
  expected_size="$(wc -c < "$local_path" | tr -d ' ')"
  remote_digest="$(printf '%s' "$draft_release" | jq -r --arg name "$name" '.assets[] | select(.name == $name) | .digest')"
  remote_size="$(printf '%s' "$draft_release" | jq -r --arg name "$name" '.assets[] | select(.name == $name) | .size')"
  if [[ "$remote_digest" != "$expected_digest" ]] || [[ "$remote_size" != "$expected_size" ]]; then
    echo "uploaded preview asset failed verification: $name" >&2
    exit 1
  fi
}

verify_uploaded_asset novex-preview.novex "$candidate_dir/novex-preview.novex"
verify_uploaded_asset zz-novex-preview-installer.apk "$candidate_dir/zz-novex-preview-installer.apk"
if ! printf '%s' "$draft_release" | jq -e '.assets[] | select(.name == "SHA256SUMS.txt")' >/dev/null; then
  echo "preview checksum manifest was not uploaded" >&2
  exit 1
fi

"$GH_BIN" release edit "$tag" \
  --draft=false \
  --prerelease \
  --notes-file "$candidate_dir/release-notes.md"

published_release="$("$GH_BIN" release view "$tag" --json targetCommitish,isDraft,isPrerelease,assets,url)"
if [[ "$(printf '%s' "$published_release" | jq -r '.targetCommitish')" != "$source_sha" ]] || \
   [[ "$(printf '%s' "$published_release" | jq -r '.isDraft')" != "false" ]] || \
   [[ "$(printf '%s' "$published_release" | jq -r '.isPrerelease')" != "true" ]]; then
  echo "published preview release failed final verification" >&2
  exit 1
fi

release_url="$(printf '%s' "$published_release" | jq -r '.url')"
receipt="$candidate_dir/PUBLISHED.env"
{
  printf 'SOURCE_SHA=%s\n' "$source_sha"
  printf 'VERSION=%s\n' "$version"
  printf 'TAG=%s\n' "$tag"
  printf 'PACKAGE=com.noven.player.preview\n'
  printf 'SIGNER_SHA256=%s\n' "$reported_cert"
  printf 'ASSET_SHA256=%s\n' "$actual_sha"
  printf 'RELEASE_URL=%s\n' "$release_url"
} > "$receipt"

printf 'published=true\n'
printf 'release_url=%s\n' "$release_url"
