#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
entry_files=(
  "$project_root/src/android/app/src/main/java/com/openminis/app/NovexLaunchActivity.kt"
  "$project_root/src/android/app/src/main/java/com/openminis/app/NovexHomeActivity.kt"
  "$project_root/src/android/app/src/main/java/com/openminis/app/ui/sessions/NovexConversationRoot.kt"
  "$project_root/src/android/app/src/main/java/com/openminis/app/ui/sessions/NovexRootScreen.kt"
)
forbidden='AppNavigation|ProviderRepository|SkillRepository|MCPRepository|sandbox\.|offload\.|browser\.|speech\.'

if rg -n "$forbidden" "${entry_files[@]}"; then
  echo "Novex lightweight startup boundary references a legacy runtime module." >&2
  exit 1
fi

root_shell="$project_root/src/android/app/src/main/java/com/openminis/app/ui/sessions/NovexRootScreen.kt"
catalog_dependencies='CharacterEntity|CharacterVersionProfile|WorldEntity|NovexArtwork|rememberNovexWorkspace|rememberNovexNativeCardImporter|existingMediaFile'

if rg -n "$catalog_dependencies" "$root_shell"; then
  echo "Novex root navigation shell still embeds catalog and media implementations." >&2
  exit 1
fi

application_shell="$project_root/src/android/app/src/main/java/com/openminis/app/MinisApp.kt"
application_bootstrap_dependencies='org\.acra\.(ReportField|config|data)|CoreConfigurationBuilder|CrashFrequencyDetector\.checkAtLaunch'

if rg -n "$application_bootstrap_dependencies" "$application_shell"; then
  echo "MinisApp still embeds crash bootstrap implementation in the manifest-loaded class." >&2
  exit 1
fi

if rg -n 'val networkMonitor[^=]*= NetworkMonitor\(' "$application_shell"; then
  echo "MinisApp eagerly creates the legacy network monitor during process startup." >&2
  exit 1
fi

echo "Novex lightweight startup boundary is free of legacy runtime references."
