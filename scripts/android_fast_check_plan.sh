#!/usr/bin/env bash

set -euo pipefail

mode="${1:-auto}"
shift || true

case "$mode" in
  full | novex-ui | skip)
    printf '%s\n' "$mode"
    exit 0
    ;;
  auto)
    ;;
  *)
    echo "unknown fast-check mode: $mode" >&2
    exit 2
    ;;
esac

if [[ "$#" -eq 0 ]]; then
  printf '%s\n' "full"
  exit 0
fi

for path in "$@"; do
  case "$path" in
    AGENTS.md | CONTEXT.md | README.md | docs/* | .github/release-notes/*)
      ;;
    src/android/app/src/main/java/com/openminis/app/ui/novex/* | \
    src/android/app/src/test/java/com/openminis/app/ui/novex/* | \
    src/android/app/src/main/java/com/openminis/app/ui/sessions/Novex* | \
    src/android/app/src/test/java/com/openminis/app/ui/sessions/Novex* | \
    src/android/app/src/main/java/com/openminis/app/ui/navigation/Novex* | \
    src/android/app/src/test/java/com/openminis/app/ui/navigation/Novex* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/World* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/World* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/Character* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/Character* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/Catalog* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/Catalog* | \
    src/android/app/src/main/java/com/openminis/app/ui/settings/NovexNativeCard* | \
    src/android/app/src/test/java/com/openminis/app/ui/settings/NovexNativeCard* | \
    src/android/app/src/main/res/drawable/novex_* | \
    src/android/app/src/main/res/drawable-nodpi/novex_*)
      ;;
    *)
      printf '%s\n' "full"
      exit 0
      ;;
  esac
done

for path in "$@"; do
  case "$path" in
    src/android/*)
      printf '%s\n' "novex-ui"
      exit 0
      ;;
  esac
done

printf '%s\n' "skip"
