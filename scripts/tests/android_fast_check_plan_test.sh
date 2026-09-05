#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLANNER="$SCRIPT_DIR/../android_fast_check_plan.sh"

assert_plan() {
  local expected="$1"
  shift
  local actual
  actual="$($PLANNER "$@")"
  if [[ "$actual" != "$expected" ]]; then
    echo "expected $expected, got: $actual" >&2
    exit 1
  fi
}

assert_plan novex-ui auto \
  src/android/app/src/main/java/com/openminis/app/ui/novex/NovexContentPage.kt \
  src/android/app/src/test/java/com/openminis/app/ui/novex/NovexVisualSystemTest.kt

assert_plan novex-domain auto \
  src/android/app/src/main/java/com/openminis/app/novex/domain/NovexConversationConfiguration.kt \
  src/android/app/src/test/java/com/openminis/app/novex/domain/NovexConversationConfigurationTest.kt

assert_plan novex-core auto \
  src/android/novex-core/src/main/kotlin/com/openminis/app/novex/domain/NovexToolContract.kt \
  src/android/novex-core/src/test/kotlin/com/openminis/app/novex/domain/NovexToolContractTest.kt

assert_plan novex-document auto \
  src/android/app/src/main/java/com/openminis/app/data/attachments/NovexDocumentSnapshotExtractor.kt \
  src/android/app/src/test/java/com/openminis/app/data/attachments/NovexDocumentSnapshotExtractorTest.kt

assert_plan novex-ui auto \
  src/android/app/src/main/java/com/openminis/app/ui/sessions/NovexRootScreen.kt \
  src/android/app/src/main/java/com/openminis/app/ui/settings/WorldCatalogScreens.kt \
  src/android/app/src/main/java/com/openminis/app/ui/settings/CharacterLibraryScreens.kt \
  src/android/app/src/test/java/com/openminis/app/ui/settings/WorldPageModulePolicyTest.kt

assert_plan full auto src/android/app/build.gradle.kts
assert_plan full auto src/android/app/src/main/java/com/openminis/app/provider/OpenAIProvider.kt
assert_plan skip auto CONTEXT.md .github/release-notes/v0.2.8.md

assert_plan full full src/android/app/src/main/java/com/openminis/app/ui/novex/NovexContentPage.kt
assert_plan novex-ui novex-ui src/android/app/build.gradle.kts
assert_plan novex-domain novex-domain src/android/app/build.gradle.kts
assert_plan novex-core novex-core src/android/app/build.gradle.kts
assert_plan novex-document novex-document src/android/app/build.gradle.kts

echo "android fast-check planner tests passed"
