#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PUBLISHER="$SCRIPT_DIR/../publish_android_preview_fast_windows.sh"

output="$(
  NOVEX_PREVIEW_RELEASE_TAGS=$'v0.2.12-beta.7\nv0.2.12-beta.30\nv0.2.11-beta.99' \
  NOVEX_FAST_PUBLISH_STATUS='' \
    "$PUBLISHER" --dry-run --skip-fetch
)"

for expected in \
  "channel=preview" \
  "tier=daily" \
  "branch=next" \
  "version=0.2.12-beta.31" \
  "tag=v0.2.12-beta.31" \
  "package=com.noven.player.preview" \
  "asset=novex-preview.novex" \
  "publish=false"; do
  if [[ "$output" != *"$expected"* ]]; then
    echo "missing dry-run output: $expected" >&2
    exit 1
  fi
done

if [[ "$output" == *"com.noven.player"$'\n'* ]] || [[ "$output" == *"channel=stable"* ]]; then
  echo "fast preview publisher must not target the stable channel" >&2
  exit 1
fi

set +e
dirty_output="$(
  NOVEX_PREVIEW_RELEASE_TAGS='v0.2.12-beta.30' \
  NOVEX_FAST_PUBLISH_STATUS=' M src/android/app/build.gradle.kts' \
    "$PUBLISHER" --dry-run --skip-fetch 2>&1
)"
dirty_status=$?
set -e
if [[ "$dirty_status" -eq 0 ]] || [[ "$dirty_output" != *"uncommitted release input"* ]]; then
  echo "fast preview publisher must reject uncommitted product changes" >&2
  exit 1
fi

set +e
branch_output="$(
  NOVEX_PREVIEW_RELEASE_TAGS='v0.2.12-beta.30' \
  NOVEX_FAST_PUBLISH_BRANCH='main' \
  NOVEX_FAST_PUBLISH_STATUS='' \
    "$PUBLISHER" --dry-run --skip-fetch 2>&1
)"
branch_status=$?
set -e
if [[ "$branch_status" -eq 0 ]] || [[ "$branch_output" != *"next branch"* ]]; then
  echo "fast preview publisher must reject every non-next branch" >&2
  exit 1
fi

echo "android preview fast-publish interface tests passed"

tag_fixture="$(mktemp -d)"
fake_tag_gh="$tag_fixture/fake-tag-gh.sh"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  "printf '%s\\n' v0.2.12-beta.30 v0.2.12-beta.26" \
  > "$fake_tag_gh"
chmod +x "$fake_tag_gh"
tag_output="$(
  NOVEX_FAST_PUBLISH_STATUS='' \
  NOVEX_GH_BIN="$fake_tag_gh" \
    "$PUBLISHER" --dry-run --skip-fetch
)"
rm -rf "$tag_fixture"
if [[ "$tag_output" != *"version=0.2.12-beta.31"* ]]; then
  echo "published releases, not a stale local tag list, must choose the next preview version" >&2
  exit 1
fi

echo "android preview remote-version tests passed"

fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT
fake_runner="$fixture_dir/fake-runner.sh"
fake_ssh="$fixture_dir/fake-ssh.sh"
artifact_bytes='signed-preview-candidate'
artifact_sha="$(printf '%s' "$artifact_bytes" | shasum -a 256 | awk '{print $1}')"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf "Verified channel=preview package=com.noven.player.preview versionName=0.2.12-beta.31\\n"' \
  'printf "candidate_apk=/remote/app-preview-release.apk\\n"' \
  "printf 'candidate_sha256=$artifact_sha\\n'" \
  "printf 'candidate_cert_sha256=cab4226b416183671281253b5f4000a28885cfa4f48146c0e7798d137e41c6a6\\n'" \
  > "$fake_runner"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  "printf '%s' '$artifact_bytes'" \
  > "$fake_ssh"
chmod +x "$fake_runner" "$fake_ssh"

candidate_output="$(
  NOVEX_PREVIEW_RELEASE_TAGS='v0.2.12-beta.30' \
  NOVEX_FAST_PUBLISH_STATUS='' \
  NOVEX_FAST_PUBLISH_CHANGED_FILES='src/android/app/src/main/java/example.kt' \
  NOVEX_FAST_CHECK_RUNNER="$fake_runner" \
  NOVEX_SSH_BIN="$fake_ssh" \
  NOVEX_FAST_PUBLISH_OUTPUT_DIR="$fixture_dir/output" \
    "$PUBLISHER" --skip-fetch
)"

for expected in \
  "published=false" \
  "candidate_sha256=$artifact_sha" \
  "candidate_dir=$fixture_dir/output/v0.2.12-beta.31"; do
  if [[ "$candidate_output" != *"$expected"* ]]; then
    echo "missing staged-candidate output: $expected" >&2
    exit 1
  fi
done

for asset in novex-preview.novex zz-novex-preview-installer.apk SHA256SUMS.txt release-notes.md; do
  if [[ ! -s "$fixture_dir/output/v0.2.12-beta.31/$asset" ]]; then
    echo "missing staged preview asset: $asset" >&2
    exit 1
  fi
done

echo "android preview candidate-staging tests passed"

fake_gh="$fixture_dir/fake-gh.sh"
gh_state="$fixture_dir/gh-state"
gh_calls="$fixture_dir/gh-calls"
source_sha="$(git -C "$SCRIPT_DIR/../.." rev-parse HEAD)"
asset_size="${#artifact_bytes}"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf "%s\\n" "$*" >> "$NOVEX_TEST_GH_CALLS"' \
  'if [[ "$1 $2" == "release view" ]]; then' \
  '  if [[ ! -f "$NOVEX_TEST_GH_STATE" ]]; then exit 1; fi' \
  '  draft=true' \
  '  [[ "$(cat "$NOVEX_TEST_GH_STATE")" == published ]] && draft=false' \
  '  printf '\''{"targetCommitish":"%s","isDraft":%s,"isPrerelease":true,"url":"https://example.invalid/release","assets":[{"name":"novex-preview.novex","size":%s,"digest":"sha256:%s"},{"name":"zz-novex-preview-installer.apk","size":%s,"digest":"sha256:%s"},{"name":"SHA256SUMS.txt","size":0,"digest":"sha256:ignored"}]}'\'' "$NOVEX_TEST_SOURCE_SHA" "$draft" "$NOVEX_TEST_ASSET_SIZE" "$NOVEX_TEST_ASSET_SHA" "$NOVEX_TEST_ASSET_SIZE" "$NOVEX_TEST_ASSET_SHA"' \
  'elif [[ "$1 $2" == "release create" ]]; then' \
  '  printf draft > "$NOVEX_TEST_GH_STATE"' \
  'elif [[ "$1 $2" == "release edit" ]]; then' \
  '  printf published > "$NOVEX_TEST_GH_STATE"' \
  'fi' \
  > "$fake_gh"
chmod +x "$fake_gh"

set +e
publish_output="$(
  NOVEX_PREVIEW_RELEASE_TAGS='v0.2.12-beta.30' \
  NOVEX_FAST_PUBLISH_STATUS='' \
  NOVEX_FAST_PUBLISH_CHANGED_FILES='src/android/app/src/main/java/example.kt' \
  NOVEX_FAST_CHECK_RUNNER="$fake_runner" \
  NOVEX_SSH_BIN="$fake_ssh" \
  NOVEX_GH_BIN="$fake_gh" \
  NOVEX_FAST_PUBLISH_OUTPUT_DIR="$fixture_dir/published-output" \
  NOVEX_TEST_GH_STATE="$gh_state" \
  NOVEX_TEST_GH_CALLS="$gh_calls" \
  NOVEX_TEST_SOURCE_SHA="$source_sha" \
  NOVEX_FAST_PUBLISH_REMOTE_SHA="$source_sha" \
  NOVEX_TEST_ASSET_SIZE="$asset_size" \
  NOVEX_TEST_ASSET_SHA="$artifact_sha" \
    "$PUBLISHER" --publish
)" 2>&1
publish_status=$?
set -e
if [[ "$publish_status" -ne 0 ]]; then
  echo "preview publication failed in fixture:" >&2
  printf '%s\n' "$publish_output" >&2
  exit 1
fi

for expected in \
  "published=true" \
  "release_url=https://example.invalid/release"; do
  if [[ "$publish_output" != *"$expected"* ]]; then
    echo "missing completed-publication output: $expected" >&2
    exit 1
  fi
done
if ! grep -q -- '--draft' "$gh_calls" || ! grep -q -- '--draft=false' "$gh_calls"; then
  echo "preview assets must be verified in a draft before publication" >&2
  exit 1
fi
if grep -q 'stable' "$gh_calls"; then
  echo "preview publication must not mutate a stable release" >&2
  exit 1
fi

echo "android preview draft-publication tests passed"
