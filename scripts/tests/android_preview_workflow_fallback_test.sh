#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOW="$SCRIPT_DIR/../../.github/workflows/android-preview.yml"

if sed -n '/^on:/,/^concurrency:/p' "$WORKFLOW" | grep -Eq '^  push:'; then
  echo "cloud preview fallback must not auto-publish after every next push" >&2
  exit 1
fi
if ! sed -n '/^on:/,/^concurrency:/p' "$WORKFLOW" | grep -Eq '^  workflow_dispatch:'; then
  echo "cloud preview fallback must remain manually available" >&2
  exit 1
fi
if ! grep -q ':app:assembleStableRelease' "$WORKFLOW" || \
   ! grep -q 'novex-stable-candidate' "$WORKFLOW"; then
  echo "the manual full candidate path must remain available for stable promotion" >&2
  exit 1
fi

echo "android preview cloud-fallback workflow tests passed"
