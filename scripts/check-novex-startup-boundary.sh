#!/usr/bin/env bash
set -euo pipefail

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
entry_files=(
  "$project_root/src/android/app/src/main/java/com/openminis/app/NovexLaunchActivity.kt"
  "$project_root/src/android/app/src/main/java/com/openminis/app/NovexHomeActivity.kt"
  "$project_root/src/android/app/src/main/java/com/openminis/app/ui/sessions/NovexConversationRoot.kt"
)
forbidden='AppNavigation|ProviderRepository|SkillRepository|MCPRepository|sandbox\.|offload\.|browser\.|speech\.'

if rg -n "$forbidden" "${entry_files[@]}"; then
  echo "Novex lightweight startup boundary references a legacy runtime module." >&2
  exit 1
fi

echo "Novex lightweight startup boundary is free of legacy runtime references."
