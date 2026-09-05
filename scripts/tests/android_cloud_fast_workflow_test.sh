#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKFLOW="$SCRIPT_DIR/../../.github/workflows/android-fast.yml"

if [[ ! -f "$WORKFLOW" ]]; then
  echo "Android cloud fast workflow is missing" >&2
  exit 1
fi

source="$(<"$WORKFLOW")"

for expected in \
  'name: Android fast lane' \
  'workflow_dispatch:' \
  'push:' \
  'branches:' \
  '- next' \
  'mode:' \
  'build_preview:' \
  'permissions:' \
  'contents: read' \
  'cancel-in-progress: true' \
  './scripts/android_fast_check_plan.sh' \
  ':novex-core:test' \
  'com.openminis.app.data.attachments.*' \
  'com.openminis.app.novex.domain.*' \
  'com.openminis.app.ui.novex.*' \
  ':app:assemblePreviewDaily' \
  'NOVEX_RELEASE_KEYSTORE_BASE64' \
  './scripts/verify_android_release.sh' \
  'actions/upload-artifact@' \
  'zz-novex-preview-installer.apk'; do
  if [[ "$source" != *"$expected"* ]]; then
    echo "Android cloud fast workflow is missing: $expected" >&2
    exit 1
  fi
done

trigger_block="$(sed -n '/^on:/,/^concurrency:/p' "$WORKFLOW")"
if [[ "$trigger_block" == *'pull_request:'* ]]; then
  echo "cloud fast lane must not duplicate the existing pull-request validation" >&2
  exit 1
fi

for forbidden in \
  'contents: write' \
  ':app:assemblePreviewRelease' \
  ':app:assembleStableRelease' \
  'gh release create' \
  'gh release edit' \
  'gh release upload'; do
  if [[ "$source" == *"$forbidden"* ]]; then
    echo "Android cloud fast workflow must not contain: $forbidden" >&2
    exit 1
  fi
done

dispatch_block="$(sed -n '/^  workflow_dispatch:/,/^  pull_request:/p' "$WORKFLOW")"
if [[ "$dispatch_block" != *'type: choice'* ]] || \
   [[ "$dispatch_block" != *'type: boolean'* ]] || \
   [[ "$dispatch_block" != *'default: false'* ]]; then
  echo "manual cloud fast inputs must use a bounded mode and opt-in package flag" >&2
  exit 1
fi

package_step="$(sed -n '/name: Run selected checks and optional daily preview/,/name: Upload daily preview/p' "$WORKFLOW")"
if [[ "$package_step" != *"github.event_name == 'workflow_dispatch'"* ]] || \
   [[ "$package_step" != *'inputs.build_preview'* ]]; then
  echo "daily preview packaging must be manual and opt-in" >&2
  exit 1
fi

if [[ "$source" != *'GITHUB_REF" != "refs/heads/next"'* ]]; then
  echo "daily preview artifacts must be restricted to next" >&2
  exit 1
fi

if [[ "$package_step" != *"secrets.NOVEX_RELEASE_STORE_PASSWORD || ''"* ]] || \
   [[ "$package_step" != *"secrets.NOVEX_RELEASE_KEY_ALIAS || ''"* ]] || \
   [[ "$package_step" != *"secrets.NOVEX_RELEASE_KEY_PASSWORD || ''"* ]]; then
  echo "preview signing values must stay absent from ordinary fast checks" >&2
  exit 1
fi

echo "Android cloud fast workflow tests passed"
