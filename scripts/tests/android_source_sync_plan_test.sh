#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLANNER="$SCRIPT_DIR/../android_source_sync_plan.sh"
fixture_dir="$(mktemp -d)"
trap 'rm -rf "$fixture_dir"' EXIT

local_manifest="$fixture_dir/local.tsv"
remote_manifest="$fixture_dir/remote.tsv"

printf 'app/A.kt\tnew-a\napp/B.kt\tsame-b\napp/C.kt\tnew-c\n' > "$local_manifest"
printf 'app/A.kt\tfailed-build-a\napp/B.kt\tsame-b\napp/RemoteOnly.kt\told\n' > "$remote_manifest"

actual="$($PLANNER "$local_manifest" "$remote_manifest")"
expected=$'app/A.kt\napp/C.kt'
if [[ "$actual" != "$expected" ]]; then
  echo "expected changed local files after remote drift:" >&2
  printf '%s\n' "$expected" >&2
  echo "got:" >&2
  printf '%s\n' "$actual" >&2
  exit 1
fi

echo "android source sync-plan tests passed"
