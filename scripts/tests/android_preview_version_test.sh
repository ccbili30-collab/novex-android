#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVER="$SCRIPT_DIR/../resolve_android_preview_version.sh"

check_version() {
  local expected="$1" actual
  shift
  actual="$(bash "$RESOLVER" "$@")" || exit 1
  [[ "$actual" == "$expected" ]] || { echo "expected $expected, received $actual" >&2; exit 1; }
}
printf '' | check_version 0.2.16-beta.31 0.2.16 beta 31
printf '%s\n' v0.2.16-beta.33 v0.2.12-beta.99 | check_version 0.2.16-beta.34 0.2.16 beta 31
printf '%s\n' v0.2.16-beta.33 v0.2.16-rc.39 | check_version 0.2.16-beta.40 0.2.16 beta 31
printf '%s\n' v0.2.16-beta.33 | check_version 0.2.16-rc.50 0.2.16 rc 50
printf '%s\n' v0x2x16-beta.99 | check_version 0.2.16-beta.31 0.2.16 beta 31
if printf '%s\n' v0.2.16-beta.9998 | bash "$RESOLVER" 0.2.16 beta 31; then
  echo 'preview numbering must not collide with the stable version code' >&2
  exit 1
fi
if printf '' | bash "$RESOLVER" 0.2.16 unknown 31; then
  echo 'unknown preview stages must be rejected' >&2
  exit 1
fi
echo 'android preview version behavior tests passed'
